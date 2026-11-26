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
add_library(voice_rnnoise SHARED bridge.c
  ${RN}/src/denoise.c ${RN}/src/rnn.c ${RN}/src/pitch.c
  ${RN}/src/kiss_fft.c ${RN}/src/celt_lpc.c ${RN}/src/nnet.c
  ${RN}/src/nnet_default.c ${RN}/src/parse_lpcnet_weights.c
  ${RN}/src/rnnoise_data.c ${RN}/src/rnnoise_tables.c)
target_include_directories(voice_rnnoise PRIVATE ${RN}/include ${RN}/src)
target_compile_options(voice_rnnoise PRIVATE -O2)
target_link_libraries(voice_rnnoise PRIVATE m)
