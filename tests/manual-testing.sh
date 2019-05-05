#!/bin/bash

# A collections of functions to test the twine-server

# Get a list of available stories
function stories {
    curl -s http://localhost:8081/stories
}

# Get the content of given story
# $1: story
function story {
    curl -s "http://localhost:8081/stories/$1"
}

# Get the exclusive editing rights to a story
# $1: story to aquire
# $2: user name
# $3: (optional) name of variable in which to store the lock id
# prints request result (lockId if successfull)
function open {
    __lockId=$(curl -s -H 'Content-Type: application/json' \
        -d "{\"user\":\"$2\"}" \
        "http://localhost:8081/stories/$1/open")
    declare -g ${3:-__lockId__}=$(sed 's/"\([^"]*\)"/\1/' <<<"$__lockId")
}

# Give up exclusive editing rights to a story
# $1: story to give up exclusive rights to
# $2: (optional) the lock Id
# prints request result
function close {
    __lockId=${2:-$__lockId__}
    curl -s -H 'Content-Type: application/json' \
        -d "{\"lock\":\"$__lockId\"}" \
        "http://localhost:8081/stories/$1/close"
}

# Renew story session rights
# $1: story
# $2: (optional) the lock Id
# prints request result
function keepup {
    __lockId=${2:-$__lockId__}
    curl -s -H 'Content-Type: application/json' \
        -d "{\"lock\":\"$__lockId\"}" \
        "http://localhost:8081/stories/$1/keepup"
}

# Save a story you own the editing rights of
# $1: story
# $2: content
# $3: (optional) the lock Id
# prints request result
function save {
    __lockId=${3:-$__lockId__}
    curl -s -H 'Content-Type: application/json' \
        -d "{\"lock\":\"$__lockId\", \"file\": \"$2\"}" \
        "http://localhost:8081/stories/$1/save"
}

