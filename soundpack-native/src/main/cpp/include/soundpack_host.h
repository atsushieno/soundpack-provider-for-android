/* The folders an app links itself (soundpack-provider-for-android, docs/PLAN.md §3.9), from
 * native code, for apps that keep their own UI (JUCE, ImGui). The app declares a
 * Local<Kind>FolderProvider for each kind; picking a folder stays with the app's own picker.
 * Same rules as soundpack.h: call soundpack_init() first, never from an audio thread. */
#ifndef SOUNDPACK_HOST_H
#define SOUNDPACK_HOST_H

#include "soundpack.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct soundpack_folder_list soundpack_folder_list;

soundpack_folder_list* soundpack_host_list_folders(void);
size_t soundpack_folder_count(const soundpack_folder_list* list);
const char* soundpack_folder_id(const soundpack_folder_list* list, size_t index);
const char* soundpack_folder_display_name(const soundpack_folder_list* list, size_t index);
const char* soundpack_folder_tree_uri(const soundpack_folder_list* list, size_t index);
/* Comma-separated kinds, e.g. "sf2" or "sfz,sf2". */
const char* soundpack_folder_kinds(const soundpack_folder_list* list, size_t index);
/* 0 when the folder cannot be read (grant revoked, folder gone); see the error. An imported
 * folder stays available from its copy. */
int soundpack_folder_available(const soundpack_folder_list* list, size_t index);
int soundpack_folder_file_count(const soundpack_folder_list* list, size_t index);
const char* soundpack_folder_error(const soundpack_folder_list* list, size_t index);
/* 1 when the folder is imported: served from a copy in the app's storage. */
int soundpack_folder_imported(const soundpack_folder_list* list, size_t index);
void soundpack_folder_list_free(soundpack_folder_list* list);

/* Links and scans a folder for [kinds] (e.g. "sf2"), which the user chose before picking it:
 * nothing is guessed from its content. [tree_uri] is a content:// tree URI the app's picker
 * returned (the app holds a grant on it). The folder is used in place, through SAF. Writes the
 * folder id into [id_out]. Returns 0 on success. */
int soundpack_host_add_folder(const char* tree_uri, const char* kinds, char* id_out, size_t id_len);
/* The same, and copies what [kinds] serve into the app's storage: files then open as plain
 * files, as from an installed pack, and keep working when the folder is gone. Blocks until the
 * copy is done, which can take minutes for a large folder. */
int soundpack_host_import_folder(const char* tree_uri, const char* kinds, char* id_out, size_t id_len);
/* Switches a folder between imported (1) and in place (0); in place deletes the copy. */
int soundpack_host_set_imported(const char* folder_id, int imported);
/* Unlinks the folder, deletes its imported copy and releases its grant. */
int soundpack_host_remove_folder(const char* folder_id);
int soundpack_host_set_kinds(const char* folder_id, const char* kinds);
/* Rescans one folder, or every folder when [folder_id] is NULL. */
int soundpack_host_rescan(const char* folder_id);

#ifdef __cplusplus
}
#endif

#endif
