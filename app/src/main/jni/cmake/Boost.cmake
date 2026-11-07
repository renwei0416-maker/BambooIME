# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_VERSION 1.92.0)

# Local-build note (not upstream): when ${CMAKE_SOURCE_DIR}/boost already
# exists (e.g. pre-extracted by hand on Windows, where extracting the archive
# into a deep source tree exceeds MAX_PATH), skip both the download and the
# REMOVE_RECURSE that would otherwise delete the prepared tree.
if(NOT EXISTS "boost-${BOOST_VERSION}.tar.xz" AND NOT EXISTS
   "${CMAKE_SOURCE_DIR}/boost")
  if(DEFINED ENV{LYRAIME_DL_CACHE}
     AND EXISTS
         "$ENV{LYRAIME_DL_CACHE}/boost-${BOOST_VERSION}-cmake.tar.xz")
    message(
      STATUS
        "Using cached Boost ${BOOST_VERSION} from $ENV{LYRAIME_DL_CACHE}")
    file(COPY "$ENV{LYRAIME_DL_CACHE}/boost-${BOOST_VERSION}-cmake.tar.xz"
         DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
    file(RENAME "${CMAKE_CURRENT_BINARY_DIR}/boost-${BOOST_VERSION}-cmake.tar.xz"
         "${CMAKE_CURRENT_BINARY_DIR}/boost-${BOOST_VERSION}.tar.xz")
  else()
    message(STATUS "Downloading Boost ${BOOST_VERSION} ......")
    file(
      DOWNLOAD
      "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
      boost-${BOOST_VERSION}.tar.xz
      EXPECTED_HASH
        SHA256=9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d
      SHOW_PROGRESS)
  endif()

  message(STATUS "Remove older version Boost")
  file(REMOVE_RECURSE "${CMAKE_SOURCE_DIR}/boost")
endif()

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/boost")
  message(STATUS "Extracting Boost ${BOOST_VERSION} ......")
  file(ARCHIVE_EXTRACT INPUT boost-${BOOST_VERSION}.tar.xz DESTINATION
       ${CMAKE_SOURCE_DIR})
  file(RENAME "boost-${BOOST_VERSION}" boost)
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    endian
    interprocess
    preprocessor
    program_options
    ptr_container
    random
    range
    regex
    scope_exit
    signals2
    system
    thread
    utility
    uuid
    vmd)

add_subdirectory(boost EXCLUDE_FROM_ALL)
