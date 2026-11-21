cmake_minimum_required(VERSION 3.22)
project(shirorun CXX)

include(FetchContent)
FetchContent_Declare(luau
    GIT_REPOSITORY https://github.com/luau-lang/luau.git
    GIT_TAG 0.632)
FetchContent_MakeAvailable(luau)

add_library(shirorun SHARED injector.cpp luau_host.cpp)
target_include_directories(shirorun PRIVATE
    ${luau_SOURCE_DIR}/Common/include
    ${luau_SOURCE_DIR}/VM/include)
target_link_libraries(shirorun android log)
