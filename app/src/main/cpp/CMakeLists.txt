cmake_minimum_required(VERSION 3.22.1)
project(voice_rnnoise C)
include(FetchContent)
FetchContent_Declare(rnnoise
  URL https://github.com/xiph/rnnoise/releases/download/v0.2/rnnoise-0.2.tar.gz
  URL_HASH SHA256=90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37)
FetchContent_GetProperties(rnnoise)
if(NOT rnnoise_POPULATED)
  FetchContent_Populate(rnnoise)
endif()
set(RN ${rnnoise_SOURCE_DIR})
# RNNoise v0.2 upstream build fix, commit 372f7b4.
# Apply once per extracted source tree; keep the release model data intact.
set(RN_FIX_URL "https://github.com/xiph/rnnoise/commit/372f7b4b76cde4ca1ec4605353dd17898a99de38.patch")
set(RN_FIX_FILE "${CMAKE_CURRENT_BINARY_DIR}/rnnoise-build-fix.patch")
if(NOT EXISTS "${RN}/.upstream-build-fix-372f7b4")
  find_program(PATCH_EXECUTABLE patch REQUIRED)
  file(DOWNLOAD "${RN_FIX_URL}" "${RN_FIX_FILE}" TLS_VERIFY ON STATUS RN_DOWNLOAD_STATUS)
  list(GET RN_DOWNLOAD_STATUS 0 RN_DOWNLOAD_CODE)
  if(NOT RN_DOWNLOAD_CODE EQUAL 0)
    message(FATAL_ERROR "RNNoise patch download failed: ${RN_DOWNLOAD_STATUS}")
  endif()
  execute_process(COMMAND "${PATCH_EXECUTABLE}" -p1 --batch --forward -i "${RN_FIX_FILE}"
    WORKING_DIRECTORY "${RN}" RESULT_VARIABLE RN_PATCH_RESULT)
  if(NOT RN_PATCH_RESULT EQUAL 0)
    message(FATAL_ERROR "RNNoise upstream patch could not be applied: ${RN_PATCH_RESULT}")
  endif()
  file(WRITE "${RN}/.upstream-build-fix-372f7b4" "Applied upstream compilation fix\n")
endif()
add_library(voice_rnnoise SHARED bridge.c
  ${RN}/src/denoise.c ${RN}/src/rnn.c ${RN}/src/pitch.c
  ${RN}/src/kiss_fft.c ${RN}/src/celt_lpc.c ${RN}/src/nnet.c
  ${RN}/src/nnet_default.c ${RN}/src/parse_lpcnet_weights.c
  ${RN}/src/rnnoise_data.c ${RN}/src/rnnoise_tables.c)
target_include_directories(voice_rnnoise PRIVATE ${RN}/include ${RN}/src)
target_compile_options(voice_rnnoise PRIVATE -O2)
target_link_libraries(voice_rnnoise PRIVATE m)
