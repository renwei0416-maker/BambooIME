cmake_minimum_required(VERSION 3.10)
project(rime-bamboo-nnlm)

aux_source_directory(src bamboo_nnlm_src)

add_library(rime-bamboo-nnlm-objs OBJECT ${bamboo_nnlm_src})

if(BUILD_SHARED_LIBS)
  set_target_properties(rime-bamboo-nnlm-objs PROPERTIES POSITION_INDEPENDENT_CODE ON)
endif()

set(plugin_name ${PROJECT_NAME} PARENT_SCOPE)
set(plugin_objs $<TARGET_OBJECTS:rime-bamboo-nnlm-objs> PARENT_SCOPE)
set(plugin_deps ${rime_library} PARENT_SCOPE)
set(plugin_modules bamboo_nnlm PARENT_SCOPE)
