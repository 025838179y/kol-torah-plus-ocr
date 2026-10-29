cmake_minimum_required(VERSION 3.22.1)
project(koltorah LANGUAGES C CXX)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.5
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(koltorah SHARED whisper_jni.cpp)
target_compile_features(koltorah PRIVATE cxx_std_17)
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(koltorah PRIVATE whisper ${log-lib} ${android-lib})
