#!/bin/sh

# This file contains some utilities to test the elasticsearch scripts,
# the .deb/.rpm packages and the SysV/Systemd scripts.

# WARNING: This testing file must be executed as root and can
# dramatically change your system. It removes the 'elasticsearch'
# user/group and also many directories. Do not execute this file
# unless you know exactly what you are doing.

# Licensed to Elasticsearch under one or more contributor
# license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright
# ownership. Elasticsearch licenses this file to you under
# the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

check_module() {
    local name=$1
    shift

    assert_module_or_plugin_directory "$ESMODULES/$name"

    for file in "$@"; do
        assert_module_or_plugin_file "$ESMODULES/$name/$file"
    done

    assert_module_or_plugin_file "$ESMODULES/$name/$name-*.jar"
    assert_module_or_plugin_file "$ESMODULES/$name/plugin-descriptor.properties"
}

check_secure_module() {
    check_module "$@" plugin-security.policy
}