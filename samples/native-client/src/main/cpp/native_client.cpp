// Uses the soundpack C API exactly as a native plugin would. Every function here is called
// from the device tests through NativeClient.
#include <jni.h>
#include <soundpack_host.h>

#include <string>
#include <unistd.h>
#include <vector>

namespace {

jobjectArray toArray(JNIEnv* env, const std::vector<std::string>& values) {
    auto array = env->NewObjectArray(static_cast<jsize>(values.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < values.size(); i++)
        env->SetObjectArrayElement(array, static_cast<jsize>(i), env->NewStringUTF(values[i].c_str()));
    return array;
}

std::string text(JNIEnv* env, jstring value) {
    if (!value)
        return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

#define NATIVE_CLIENT(name) Java_org_androidaudioplugin_soundpack_sample_nativeclient_NativeClient_##name

extern "C" JNIEXPORT jint JNICALL NATIVE_CLIENT(init)(JNIEnv* env, jclass, jobject context) {
    return soundpack_init(env, context);
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CLIENT(lastError)(JNIEnv* env, jclass) {
    return env->NewStringUTF(soundpack_last_error());
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CLIENT(formatIdentity)(JNIEnv* env, jclass, jstring kind, jstring packId, jstring path) {
    char identity[1024];
    if (soundpack_format_identity(text(env, kind).c_str(), text(env, packId).c_str(), text(env, path).c_str(), identity, sizeof identity))
        return nullptr;
    return env->NewStringUTF(identity);
}

extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CLIENT(listEntries)(JNIEnv* env, jclass, jstring kind) {
    std::vector<std::string> fields;
    if (auto* list = soundpack_list_entries(text(env, kind).c_str())) {
        for (size_t i = 0; i < soundpack_entry_list_count(list); i++) {
            fields.emplace_back(soundpack_entry_identity(list, i));
            fields.emplace_back(soundpack_entry_pack_label(list, i));
            fields.emplace_back(soundpack_entry_path(list, i));
            fields.emplace_back(soundpack_entry_label(list, i));
            fields.emplace_back(soundpack_entry_group(list, i));
            fields.emplace_back(soundpack_entry_metadata(list, i));
        }
        soundpack_entry_list_free(list);
    }
    return toArray(env, fields);
}

extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CLIENT(describe)(JNIEnv* env, jclass, jstring identity) {
    auto* session = soundpack_open(text(env, identity).c_str());
    if (!session)
        return nullptr;
    std::vector<std::string> result;
    const std::string entry = soundpack_session_entry(session);
    const auto count = soundpack_session_file_count(session);

    std::vector<const char*> paths;
    for (size_t i = 0; i < count; i++)
        paths.push_back(soundpack_session_file_path(session, i));
    bool ok = soundpack_session_prefetch(session, paths.data(), paths.size()) == 0;

    soundpack_mapping mapping;
    int fd = -1;
    int64_t offset = 0, length = 0;
    if (ok && soundpack_session_map(session, entry.c_str(), &mapping) == 0) {
        std::string head(static_cast<const char*>(mapping.data), mapping.size < 12 ? mapping.size : 12);
        for (auto& c : head)
            if (c < 0x20 || c > 0x7e)
                c = '.';
        result = { entry, std::to_string(count), std::to_string(mapping.size), head };
        soundpack_mapping_release(&mapping);
        if (soundpack_session_open_fd(session, entry.c_str(), &fd, &offset, &length) == 0) {
            close(fd);
            result.push_back(std::to_string(offset));
            result.push_back(std::to_string(length));
        } else {
            ok = false;
        }
    } else {
        ok = false;
    }
    soundpack_session_close(session);
    return ok ? toArray(env, result) : nullptr;
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CLIENT(addFolder)(JNIEnv* env, jclass, jstring treeUri, jstring kinds) {
    char id[128];
    if (soundpack_host_add_folder(text(env, treeUri).c_str(), text(env, kinds).c_str(), id, sizeof id) != 0)
        return nullptr;
    return env->NewStringUTF(id);
}

extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CLIENT(folders)(JNIEnv* env, jclass) {
    std::vector<std::string> fields;
    if (auto* list = soundpack_host_list_folders()) {
        for (size_t i = 0; i < soundpack_folder_count(list); i++) {
            fields.emplace_back(soundpack_folder_id(list, i));
            fields.emplace_back(soundpack_folder_display_name(list, i));
            fields.emplace_back(soundpack_folder_tree_uri(list, i));
            fields.emplace_back(soundpack_folder_kinds(list, i));
            fields.emplace_back(soundpack_folder_available(list, i) ? "1" : "0");
            fields.emplace_back(std::to_string(soundpack_folder_file_count(list, i)));
            fields.emplace_back(soundpack_folder_error(list, i));
        }
        soundpack_folder_list_free(list);
    }
    return toArray(env, fields);
}

extern "C" JNIEXPORT jint JNICALL NATIVE_CLIENT(removeFolder)(JNIEnv* env, jclass, jstring id) {
    return soundpack_host_remove_folder(text(env, id).c_str());
}

extern "C" JNIEXPORT jint JNICALL NATIVE_CLIENT(rescan)(JNIEnv* env, jclass, jstring id) {
    return id ? soundpack_host_rescan(text(env, id).c_str()) : soundpack_host_rescan(nullptr);
}
