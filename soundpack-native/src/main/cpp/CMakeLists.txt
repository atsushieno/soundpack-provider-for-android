cmake_minimum_required(VERSION 3.22)
project(soundpack C)

# Plain C with no C++ runtime (ANDROID_STL=none): it links into apps built with any STL.
add_library(soundpack SHARED soundpack.c)
target_include_directories(soundpack PUBLIC include)
set_target_properties(soundpack PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
target_compile_options(soundpack PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
target_link_libraries(soundpack PRIVATE log)
