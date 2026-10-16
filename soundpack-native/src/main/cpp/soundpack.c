/* soundpack.h and soundpack_host.h over JNI: every function calls into SoundpackNative
 * (Kotlin), which holds all the logic. Plain C, so the library works with any C++ runtime. */
#include "soundpack.h"
#include "soundpack_host.h"

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define BRIDGE_CLASS "org.androidaudioplugin.soundpack.ndk.SoundpackNative"

static JavaVM* vm;
static jclass bridge;
static size_t entry_fields;
static size_t folder_fields;
static jmethodID m_format_identity, m_list_entries, m_open, m_session_info, m_open_fd, m_prefetch, m_close, m_materialize;
static jmethodID m_host_folders, m_host_add_folder, m_host_remove_folder, m_host_set_kinds, m_host_rescan, m_host_set_imported;

static __thread char last_error[512];

static void set_error(const char* format, ...) {
    va_list args;
    va_start(args, format);
    vsnprintf(last_error, sizeof last_error, format, args);
    va_end(args);
}

const char* soundpack_last_error(void) { return last_error; }

/* A JNIEnv for this thread, attaching it if needed, inside a local frame. */
typedef struct {
    JNIEnv* env;
    int attached;
} jni_scope;

static int scope_enter(jni_scope* scope) {
    if (!vm || !bridge) {
        set_error("soundpack_init() was not called");
        return -1;
    }
    last_error[0] = 0;
    scope->attached = 0;
    if ((*vm)->GetEnv(vm, (void**) &scope->env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &scope->env, NULL) != JNI_OK) {
            set_error("Cannot attach this thread to the JVM");
            return -1;
        }
        scope->attached = 1;
    }
    if ((*scope->env)->PushLocalFrame(scope->env, 32) < 0) {
        (*scope->env)->ExceptionClear(scope->env);
        if (scope->attached)
            (*vm)->DetachCurrentThread(vm);
        set_error("Out of JNI local references");
        return -1;
    }
    return 0;
}

static void scope_leave(jni_scope* scope) {
    (*scope->env)->PopLocalFrame(scope->env, NULL);
    if (scope->attached)
        (*vm)->DetachCurrentThread(vm);
}

/* Turns a pending Java exception into the last error. Returns 1 if there was one. */
static int failed(JNIEnv* env, const char* what) {
    if (!(*env)->ExceptionCheck(env))
        return 0;
    jthrowable error = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    jmethodID to_string = object ? (*env)->GetMethodID(env, object, "toString", "()Ljava/lang/String;") : NULL;
    jstring text = to_string ? (jstring) (*env)->CallObjectMethod(env, error, to_string) : NULL;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        text = NULL;
    }
    const char* chars = text ? (*env)->GetStringUTFChars(env, text, NULL) : NULL;
    set_error("%s: %s", what, chars ? chars : "failed");
    if (chars)
        (*env)->ReleaseStringUTFChars(env, text, chars);
    return 1;
}

static jbyteArray bytes_of(JNIEnv* env, const char* text) {
    size_t length = text ? strlen(text) : 0;
    jbyteArray array = (*env)->NewByteArray(env, (jsize) length);
    if (array && length)
        (*env)->SetByteArrayRegion(env, array, 0, (jsize) length, (const jbyte*) text);
    return array;
}

/* NUL-terminated UTF-8 fields, copied out of a byte array into one block. */
typedef struct {
    char* buffer;
    const char** fields;
    size_t count;
} field_table;

static int fields_of(JNIEnv* env, jbyteArray array, field_table* out) {
    memset(out, 0, sizeof *out);
    jsize length = array ? (*env)->GetArrayLength(env, array) : 0;
    out->buffer = malloc((size_t) length + 1);
    if (!out->buffer) {
        set_error("Out of memory");
        return -1;
    }
    if (length)
        (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte*) out->buffer);
    out->buffer[length] = 0;
    size_t count = 0;
    for (jsize i = 0; i < length; i++)
        if (out->buffer[i] == 0)
            count++;
    out->fields = malloc((count ? count : 1) * sizeof *out->fields);
    if (!out->fields) {
        free(out->buffer);
        out->buffer = NULL;
        set_error("Out of memory");
        return -1;
    }
    const char* field = out->buffer;
    for (size_t i = 0; i < count; i++) {
        out->fields[i] = field;
        field += strlen(field) + 1;
    }
    out->count = count;
    return 0;
}

static void fields_free(field_table* table) {
    free(table->buffer);
    free((void*) table->fields);
    memset(table, 0, sizeof *table);
}

static const char* field_at(const field_table* table, size_t per_item, size_t index, size_t field) {
    if (!table || per_item == 0 || index >= table->count / per_item)
        return "";
    return table->fields[index * per_item + field];
}

int soundpack_init(JNIEnv* env, jobject context) {
    if (!env || !context) {
        set_error("soundpack_init() needs a JNIEnv and a Context");
        return -1;
    }
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) {
        set_error("Cannot get the JavaVM");
        return -1;
    }
    if ((*env)->PushLocalFrame(env, 32) < 0) {
        (*env)->ExceptionClear(env);
        set_error("Out of JNI local references");
        return -1;
    }
    int result = -1;
    /* Through the app's class loader: FindClass() on a thread JNI attached itself only sees
     * the system classes. */
    jclass context_class = (*env)->GetObjectClass(env, context);
    jmethodID get_class_loader = (*env)->GetMethodID(env, context_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = get_class_loader ? (*env)->CallObjectMethod(env, context, get_class_loader) : NULL;
    jclass loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
    jmethodID load_class = loader_class ? (*env)->GetMethodID(env, loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    jclass found = (loader && load_class)
        ? (jclass) (*env)->CallObjectMethod(env, loader, load_class, (*env)->NewStringUTF(env, BRIDGE_CLASS)) : NULL;
    if (failed(env, "Loading " BRIDGE_CLASS) || !found) {
        if (!last_error[0])
            set_error("Cannot load " BRIDGE_CLASS);
        goto done;
    }

#define STATIC_METHOD(target, name, signature) \
    if (!((target) = (*env)->GetStaticMethodID(env, found, name, signature))) { failed(env, "Finding " name); goto done; }
    jmethodID m_init, m_entry_fields, m_folder_fields;
    STATIC_METHOD(m_init, "init", "(Landroid/content/Context;)V")
    STATIC_METHOD(m_entry_fields, "entryFields", "()I")
    STATIC_METHOD(m_folder_fields, "folderFields", "()I")
    STATIC_METHOD(m_format_identity, "formatIdentity", "([B[B[B)[B")
    STATIC_METHOD(m_list_entries, "listEntries", "([B)[B")
    STATIC_METHOD(m_open, "open", "([B)Ljava/lang/Object;")
    STATIC_METHOD(m_session_info, "sessionInfo", "(Ljava/lang/Object;)[B")
    STATIC_METHOD(m_open_fd, "openFd", "(Ljava/lang/Object;[B)[J")
    STATIC_METHOD(m_prefetch, "prefetch", "(Ljava/lang/Object;[B)V")
    STATIC_METHOD(m_close, "close", "(Ljava/lang/Object;)V")
    STATIC_METHOD(m_materialize, "materialize", "([B[B[B)[I")
    STATIC_METHOD(m_host_folders, "hostFolders", "()[B")
    STATIC_METHOD(m_host_add_folder, "hostAddFolder", "([B[BZ)[B")
    STATIC_METHOD(m_host_set_imported, "hostSetImported", "([BZ)V")
    STATIC_METHOD(m_host_remove_folder, "hostRemoveFolder", "([B)V")
    STATIC_METHOD(m_host_set_kinds, "hostSetKinds", "([B[B)V")
    STATIC_METHOD(m_host_rescan, "hostRescan", "([B)V")
#undef STATIC_METHOD

    (*env)->CallStaticVoidMethod(env, found, m_init, context);
    entry_fields = (size_t) (*env)->CallStaticIntMethod(env, found, m_entry_fields);
    folder_fields = (size_t) (*env)->CallStaticIntMethod(env, found, m_folder_fields);
    if (failed(env, "Initializing soundpacks"))
        goto done;

    jclass previous = bridge;
    bridge = (jclass) (*env)->NewGlobalRef(env, found);
    if (previous)
        (*env)->DeleteGlobalRef(env, previous);
    result = 0;
done:
    (*env)->PopLocalFrame(env, NULL);
    return result;
}

/* Copies a byte array into [out] as a C string. Returns 0 if it fits. */
static int copy_out(JNIEnv* env, jbyteArray bytes, char* out, size_t out_length, const char* what) {
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (!out || out_length <= (size_t) length) {
        set_error("%s needs %d bytes", what, (int) length + 1);
        return -1;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte*) out);
    out[length] = 0;
    return 0;
}

int soundpack_format_identity(const char* kind, const char* pack_id, const char* entry_path, char* out, size_t out_length) {
    if (!kind || !pack_id || !entry_path) {
        set_error("Invalid arguments");
        return -1;
    }
    jni_scope scope;
    if (scope_enter(&scope))
        return -1;
    JNIEnv* env = scope.env;
    int result = -1;
    jbyteArray identity = (jbyteArray) (*env)->CallStaticObjectMethod(env, bridge, m_format_identity,
        bytes_of(env, kind), bytes_of(env, pack_id), bytes_of(env, entry_path));
    if (!failed(env, "Formatting an identity") && identity)
        result = copy_out(env, identity, out, out_length, "The identity");
    scope_leave(&scope);
    return result;
}

/* ---- Entries ---- */

struct soundpack_entry_list {
    field_table table;
};

soundpack_entry_list* soundpack_list_entries(const char* kind) {
    jni_scope scope;
    if (scope_enter(&scope))
        return NULL;
    JNIEnv* env = scope.env;
    soundpack_entry_list* list = NULL;
    jbyteArray result = (jbyteArray) (*env)->CallStaticObjectMethod(env, bridge, m_list_entries, bytes_of(env, kind));
    if (!failed(env, "Listing soundpack entries")) {
        list = calloc(1, sizeof *list);
        if (!list)
            set_error("Out of memory");
        else if (fields_of(env, result, &list->table)) {
            free(list);
            list = NULL;
        }
    }
    scope_leave(&scope);
    return list;
}

size_t soundpack_entry_list_count(const soundpack_entry_list* list) {
    return list && entry_fields ? list->table.count / entry_fields : 0;
}

const char* soundpack_entry_identity(const soundpack_entry_list* l, size_t i) { return field_at(l ? &l->table : NULL, entry_fields, i, 0); }
const char* soundpack_entry_pack_label(const soundpack_entry_list* l, size_t i) { return field_at(l ? &l->table : NULL, entry_fields, i, 1); }
const char* soundpack_entry_path(const soundpack_entry_list* l, size_t i) { return field_at(l ? &l->table : NULL, entry_fields, i, 2); }
const char* soundpack_entry_label(const soundpack_entry_list* l, size_t i) { return field_at(l ? &l->table : NULL, entry_fields, i, 3); }
const char* soundpack_entry_group(const soundpack_entry_list* l, size_t i) { return field_at(l ? &l->table : NULL, entry_fields, i, 4); }
const char* soundpack_entry_metadata(const soundpack_entry_list* l, size_t i) { return field_at(l ? &l->table : NULL, entry_fields, i, 5); }

void soundpack_entry_list_free(soundpack_entry_list* list) {
    if (!list)
        return;
    fields_free(&list->table);
    free(list);
}

/* ---- Sessions ---- */

struct soundpack_session {
    jobject session;
    field_table info; /* the entry path, then every file path */
};

soundpack_session* soundpack_open(const char* identity) {
    jni_scope scope;
    if (scope_enter(&scope))
        return NULL;
    JNIEnv* env = scope.env;
    soundpack_session* session = NULL;
    jobject opened = (*env)->CallStaticObjectMethod(env, bridge, m_open, bytes_of(env, identity));
    if (failed(env, "Opening soundpack"))
        goto done;
    jbyteArray info = (jbyteArray) (*env)->CallStaticObjectMethod(env, bridge, m_session_info, opened);
    if (failed(env, "Listing the pack's files"))
        goto done;
    session = calloc(1, sizeof *session);
    if (!session) {
        set_error("Out of memory");
        goto done;
    }
    if (fields_of(env, info, &session->info)) {
        free(session);
        session = NULL;
        goto done;
    }
    session->session = (*env)->NewGlobalRef(env, opened);
done:
    scope_leave(&scope);
    return session;
}

const char* soundpack_session_entry(const soundpack_session* session) {
    return session && session->info.count ? session->info.fields[0] : "";
}

size_t soundpack_session_file_count(const soundpack_session* session) {
    return session && session->info.count ? session->info.count - 1 : 0;
}

const char* soundpack_session_file_path(const soundpack_session* session, size_t index) {
    return index < soundpack_session_file_count(session) ? session->info.fields[index + 1] : "";
}

int soundpack_session_prefetch(soundpack_session* session, const char* const* paths, size_t count) {
    if (!session || (count && !paths)) {
        set_error("Invalid arguments");
        return -1;
    }
    size_t length = 0;
    for (size_t i = 0; i < count; i++)
        length += strlen(paths[i]) + 1;
    char* joined = malloc(length ? length : 1);
    if (!joined) {
        set_error("Out of memory");
        return -1;
    }
    char* cursor = joined;
    for (size_t i = 0; i < count; i++) {
        size_t n = strlen(paths[i]) + 1;
        memcpy(cursor, paths[i], n);
        cursor += n;
    }
    jni_scope scope;
    int result = -1;
    if (scope_enter(&scope) == 0) {
        JNIEnv* env = scope.env;
        jbyteArray array = (*env)->NewByteArray(env, (jsize) length);
        if (array && length)
            (*env)->SetByteArrayRegion(env, array, 0, (jsize) length, (const jbyte*) joined);
        (*env)->CallStaticVoidMethod(env, bridge, m_prefetch, session->session, array);
        result = failed(env, "Prefetching") ? -1 : 0;
        scope_leave(&scope);
    }
    free(joined);
    return result;
}

int soundpack_session_open_fd(soundpack_session* session, const char* path, int* fd, int64_t* offset, int64_t* length) {
    if (!session || !path || !fd || !offset || !length) {
        set_error("Invalid arguments");
        return -1;
    }
    jni_scope scope;
    if (scope_enter(&scope))
        return -1;
    JNIEnv* env = scope.env;
    int result = -1;
    jlongArray opened = (jlongArray) (*env)->CallStaticObjectMethod(env, bridge, m_open_fd, session->session, bytes_of(env, path));
    if (!failed(env, "Opening a soundpack file") && opened) {
        jlong values[3];
        (*env)->GetLongArrayRegion(env, opened, 0, 3, values);
        *fd = (int) values[0];
        *offset = values[1];
        *length = values[2];
        result = 0;
    }
    scope_leave(&scope);
    return result;
}

int soundpack_session_map(soundpack_session* session, const char* path, soundpack_mapping* out) {
    if (!out) {
        set_error("Invalid arguments");
        return -1;
    }
    memset(out, 0, sizeof *out);
    int fd;
    int64_t offset, length;
    if (soundpack_session_open_fd(session, path, &fd, &offset, &length))
        return -1;
    int result = -1;
    struct stat info;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || offset < 0 || length <= 0 || fstat(fd, &info) || !S_ISREG(info.st_mode)
            || offset > info.st_size || length > info.st_size - offset) {
        set_error("%s is not a mappable file range", path);
        goto done;
    }
    int64_t displacement = offset % page;
    if ((uint64_t) length > SIZE_MAX - (uint64_t) displacement) {
        set_error("%s is too large to map", path);
        goto done;
    }
    size_t mapped_length = (size_t) (length + displacement);
    void* base = mmap(NULL, mapped_length, PROT_READ, MAP_PRIVATE, fd, (off_t) (offset - displacement));
    if (base == MAP_FAILED) {
        set_error("Cannot map %s", path);
        goto done;
    }
    out->data = (const char*) base + displacement;
    out->size = (size_t) length;
    out->base_ = base;
    out->base_length_ = mapped_length;
    result = 0;
done:
    close(fd);
    return result;
}

void soundpack_mapping_release(soundpack_mapping* mapping) {
    if (!mapping || !mapping->base_)
        return;
    munmap(mapping->base_, mapping->base_length_);
    memset(mapping, 0, sizeof *mapping);
}

void soundpack_session_close(soundpack_session* session) {
    if (!session)
        return;
    jni_scope scope;
    if (scope_enter(&scope) == 0) {
        JNIEnv* env = scope.env;
        (*env)->CallStaticVoidMethod(env, bridge, m_close, session->session);
        failed(env, "Closing a soundpack session");
        (*env)->DeleteGlobalRef(env, session->session);
        scope_leave(&scope);
    }
    fields_free(&session->info);
    free(session);
}

int soundpack_materialize(const char* kind, const char* pack_id, const char* dest_dir) {
    jni_scope scope;
    if (scope_enter(&scope))
        return -1;
    JNIEnv* env = scope.env;
    int result = -1;
    jintArray counts = (jintArray) (*env)->CallStaticObjectMethod(env, bridge, m_materialize,
        bytes_of(env, kind), bytes_of(env, pack_id), bytes_of(env, dest_dir));
    if (!failed(env, "Materializing a soundpack") && counts) {
        jint values[3];
        (*env)->GetIntArrayRegion(env, counts, 0, 3, values);
        result = values[0];
    }
    scope_leave(&scope);
    return result;
}

/* ---- Linked folders ---- */

struct soundpack_folder_list {
    field_table table;
};

soundpack_folder_list* soundpack_host_list_folders(void) {
    jni_scope scope;
    if (scope_enter(&scope))
        return NULL;
    JNIEnv* env = scope.env;
    soundpack_folder_list* list = NULL;
    jbyteArray result = (jbyteArray) (*env)->CallStaticObjectMethod(env, bridge, m_host_folders);
    if (!failed(env, "Listing folders")) {
        list = calloc(1, sizeof *list);
        if (!list)
            set_error("Out of memory");
        else if (fields_of(env, result, &list->table)) {
            free(list);
            list = NULL;
        }
    }
    scope_leave(&scope);
    return list;
}

size_t soundpack_folder_count(const soundpack_folder_list* list) {
    return list && folder_fields ? list->table.count / folder_fields : 0;
}

const char* soundpack_folder_id(const soundpack_folder_list* l, size_t i) { return field_at(l ? &l->table : NULL, folder_fields, i, 0); }
const char* soundpack_folder_display_name(const soundpack_folder_list* l, size_t i) { return field_at(l ? &l->table : NULL, folder_fields, i, 1); }
const char* soundpack_folder_tree_uri(const soundpack_folder_list* l, size_t i) { return field_at(l ? &l->table : NULL, folder_fields, i, 2); }
const char* soundpack_folder_kinds(const soundpack_folder_list* l, size_t i) { return field_at(l ? &l->table : NULL, folder_fields, i, 3); }
int soundpack_folder_available(const soundpack_folder_list* l, size_t i) { return strcmp(field_at(l ? &l->table : NULL, folder_fields, i, 4), "1") == 0; }
int soundpack_folder_file_count(const soundpack_folder_list* l, size_t i) { return atoi(field_at(l ? &l->table : NULL, folder_fields, i, 5)); }
const char* soundpack_folder_error(const soundpack_folder_list* l, size_t i) { return field_at(l ? &l->table : NULL, folder_fields, i, 6); }
int soundpack_folder_imported(const soundpack_folder_list* l, size_t i) { return strcmp(field_at(l ? &l->table : NULL, folder_fields, i, 7), "1") == 0; }

void soundpack_folder_list_free(soundpack_folder_list* list) {
    if (!list)
        return;
    fields_free(&list->table);
    free(list);
}

static int add_folder(const char* tree_uri, const char* kinds, jboolean import, char* id_out, size_t id_len) {
    if (!tree_uri || !kinds) {
        set_error("Invalid arguments");
        return -1;
    }
    jni_scope scope;
    if (scope_enter(&scope))
        return -1;
    JNIEnv* env = scope.env;
    int result = -1;
    jbyteArray id = (jbyteArray) (*env)->CallStaticObjectMethod(env, bridge, m_host_add_folder,
        bytes_of(env, tree_uri), bytes_of(env, kinds), import);
    if (!failed(env, import ? "Importing a folder" : "Linking a folder") && id)
        result = id_out ? copy_out(env, id, id_out, id_len, "The folder id") : 0;
    scope_leave(&scope);
    return result;
}

int soundpack_host_add_folder(const char* tree_uri, const char* kinds, char* id_out, size_t id_len) {
    return add_folder(tree_uri, kinds, JNI_FALSE, id_out, id_len);
}

int soundpack_host_import_folder(const char* tree_uri, const char* kinds, char* id_out, size_t id_len) {
    return add_folder(tree_uri, kinds, JNI_TRUE, id_out, id_len);
}

int soundpack_host_set_imported(const char* folder_id, int imported) {
    if (!folder_id) {
        set_error("Invalid arguments");
        return -1;
    }
    jni_scope scope;
    if (scope_enter(&scope))
        return -1;
    JNIEnv* env = scope.env;
    (*env)->CallStaticVoidMethod(env, bridge, m_host_set_imported, bytes_of(env, folder_id), imported ? JNI_TRUE : JNI_FALSE);
    int result = failed(env, imported ? "Importing a folder" : "Using a folder in place") ? -1 : 0;
    scope_leave(&scope);
    return result;
}

static int host_call(jmethodID method, const char* what, const char* first, const char* second) {
    jni_scope scope;
    if (scope_enter(&scope))
        return -1;
    JNIEnv* env = scope.env;
    if (second)
        (*env)->CallStaticVoidMethod(env, bridge, method, bytes_of(env, first), bytes_of(env, second));
    else
        (*env)->CallStaticVoidMethod(env, bridge, method, bytes_of(env, first));
    int result = failed(env, what) ? -1 : 0;
    scope_leave(&scope);
    return result;
}

int soundpack_host_remove_folder(const char* folder_id) {
    if (!folder_id) {
        set_error("Invalid arguments");
        return -1;
    }
    return host_call(m_host_remove_folder, "Removing a folder", folder_id, NULL);
}

int soundpack_host_set_kinds(const char* folder_id, const char* kinds) {
    if (!folder_id || !kinds) {
        set_error("Invalid arguments");
        return -1;
    }
    return host_call(m_host_set_kinds, "Changing a folder's kinds", folder_id, kinds);
}

int soundpack_host_rescan(const char* folder_id) {
    return host_call(m_host_rescan, "Rescanning folders", folder_id ? folder_id : "", NULL);
}
