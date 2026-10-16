cmake_minimum_required(VERSION 3.22)
project(native-client CXX)

find_package(soundpack-native REQUIRED CONFIG)

add_library(native-client SHARED native_client.cpp)
target_compile_features(native-client PRIVATE cxx_std_17)
target_link_libraries(native-client PRIVATE soundpack-native::soundpack)
