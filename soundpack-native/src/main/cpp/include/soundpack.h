/* Soundpacks from native code (soundpack-provider-for-android, docs/PLAN.md §4).
 *
 * A C ABI over the Kotlin client, so that engines built with any C++ runtime (sfizz, JUCE
 * apps, SDL apps) can use it. Every function may block on IPC: call them from any thread
 * except an audio thread. Strings are UTF-8, and those returned are owned by the object they
 * come from. Functions that fail return NULL or a negative value, and leave a message in
 * soundpack_last_error(). */
#ifndef SOUNDPACK_H
#define SOUNDPACK_H

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Once per process, before any other call, from a thread that has a JNIEnv; [context] is any
 * Android Context of the app. JUCE apps pass juce::getEnv() and juce::getAppContext(); SDL
 * apps pass SDL_GetAndroidJNIEnv() and SDL_GetAndroidActivity(). Returns 0 on success. */
int soundpack_init(JNIEnv* env, jobject context);

/* The message of the last failure on this thread, or "" if none. */
const char* soundpack_last_error(void);

/* Writes the identity of [entry_path] in pack [pack_id] of [kind] into [out]: what
 * soundpack_entry_identity() gives for that entry, without the provider hint. For a pack
 * whose id the app knows, e.g. a linked folder's (soundpack_host.h). Returns 0 on success. */
int soundpack_format_identity(const char* kind, const char* pack_id, const char* entry_path, char* out, size_t out_length);

/* Entries of one kind ("sfz", "sf2", "jsfx") from every source the app can read: installed
 * pack apps, its own linked folders, and the folders shared in the Soundpack Manager. */
typedef struct soundpack_entry_list soundpack_entry_list;

soundpack_entry_list* soundpack_list_entries(const char* kind);
size_t soundpack_entry_list_count(const soundpack_entry_list* list);
/* What a state saves to find the entry again: "commons-soundpack://...". */
const char* soundpack_entry_identity(const soundpack_entry_list* list, size_t index);
const char* soundpack_entry_pack_label(const soundpack_entry_list* list, size_t index);
/* The entry's path within its pack. */
const char* soundpack_entry_path(const soundpack_entry_list* list, size_t index);
const char* soundpack_entry_label(const soundpack_entry_list* list, size_t index);
const char* soundpack_entry_group(const soundpack_entry_list* list, size_t index);
/* Kind-specific JSON, "" when none; SF2: {"presets":[{"bank":0,"program":0,"name":"..."}]}. */
const char* soundpack_entry_metadata(const soundpack_entry_list* list, size_t index);
void soundpack_entry_list_free(soundpack_entry_list* list);

/* The files of the pack an identity points to. */
typedef struct soundpack_session soundpack_session;

soundpack_session* soundpack_open(const char* identity);
/* The entry's path within the pack; open it with the functions below. */
const char* soundpack_session_entry(const soundpack_session* session);
size_t soundpack_session_file_count(const soundpack_session* session);
const char* soundpack_session_file_path(const soundpack_session* session, size_t index);
/* Starts opening [paths] in the background, in this order, for an engine that then asks for
 * them one at a time. Returns 0 on success. */
int soundpack_session_prefetch(soundpack_session* session, const char* const* paths, size_t count);
/* A read-only descriptor the caller must close: the file is the [length] bytes from [offset]
 * (an APK asset is a range of the APK). Returns 0 on success. */
int soundpack_session_open_fd(soundpack_session* session, const char* path, int* fd, int64_t* offset, int64_t* length);

typedef struct soundpack_mapping {
    const void* data;    /* the file's bytes */
    size_t size;
    void* base_;         /* private */
    size_t base_length_; /* private */
} soundpack_mapping;

/* Maps a file read-only. The mapping outlives the session; release it when done. */
int soundpack_session_map(soundpack_session* session, const char* path, soundpack_mapping* out);
void soundpack_mapping_release(soundpack_mapping* mapping);
void soundpack_session_close(soundpack_session* session);

/* Mirrors a pack into [dest_dir] for engines that need real paths (JSFX), writing only what
 * changed. Returns the number of files written, or a negative value. */
int soundpack_materialize(const char* kind, const char* pack_id, const char* dest_dir);

#ifdef __cplusplus
}
#endif

#endif
