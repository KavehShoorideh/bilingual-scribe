cmake_minimum_required(VERSION 3.22)
project(bscribe_asr CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# android/asr/src/main/cpp -> repo root is five levels up.
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/whisper.cpp)

if(NOT EXISTS ${WHISPER_LIB_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "third_party/whisper.cpp is empty. Run: git submodule update --init --recursive")
endif()

# Stamped into the binary so a transcript can be traced to the engine that
# produced it, alongside the model hash in ModelFiles.modelId().
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" _WHISPER_MAIN_CMAKE)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)"
       _MATCH "${_WHISPER_MAIN_CMAKE}")
set(WHISPER_VERSION ${CMAKE_MATCH_1})
message(STATUS "bscribe: whisper.cpp ${WHISPER_VERSION}, ABI ${ANDROID_ABI}")

# The FP4's Adreno 619 crashes under ggml's Vulkan backend (16-bit storage
# unsupported -> DeviceLost), so this is CPU-only by decision, not omission.
# See docs/architecture.md.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # NDK ships no linkable libomp
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)   # cross-compiling; -march is set below
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# armv8.2 gives fp16 arithmetic and the dot-product instructions ggml's
# quantized kernels lean on. The FP4's Kryo 570 supports both; this is why
# :asr is arm64-only.
set(BSCRIBE_ARCH_FLAGS -march=armv8.2-a+fp16+dotprod)

find_library(LOG_LIB log)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
target_compile_options(ggml PRIVATE ${BSCRIBE_ARCH_FLAGS})

add_library(bscribe_asr SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    whisper_jni.cpp
)

target_compile_definitions(bscribe_asr PUBLIC GGML_USE_CPU)
target_compile_definitions(bscribe_asr PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
target_compile_options(bscribe_asr PRIVATE ${BSCRIBE_ARCH_FLAGS})

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(bscribe_asr PRIVATE
        -O3 -fvisibility=hidden -fvisibility-inlines-hidden
        -ffunction-sections -fdata-sections)
    target_link_options(bscribe_asr PRIVATE
        -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

target_include_directories(bscribe_asr PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

target_link_libraries(bscribe_asr ${LOG_LIB} android ggml)
