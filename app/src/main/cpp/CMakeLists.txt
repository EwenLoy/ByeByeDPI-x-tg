cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

set(BYE_DPI_SRC
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/conev.c
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/desync.c
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/extend.c
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/main.c
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/mpool.c
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/packets.c
    ${CMAKE_CURRENT_LIST_DIR}/byedpi/proxy.c
)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c)
target_include_directories(byedpi PRIVATE byedpi)

target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log)
