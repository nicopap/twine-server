#!/bin/bash

# A collections of functions to test the twine-server

# Get a list of available stories
function stories {
    curl http://localhost:8081/stories
}

# Get the content of given story
# $1: story
function story {
    curl "http://localhost:8081/stories/$1"
}

# Get the exclusive editing rights to a story
# $1: story to aquire
# $2: user name
# prints request result (lockId if successfull)
function open {
    curl -H 'Content-Type: application/json' \
        -d "{\"user\":\"$2\"}" \
        "http://localhost:8081/stories/$1/open"
}

# Give up exclusive editing rights to a story
# $1: story to give up exclusive rights to
# $2: lockid
# prints request result
function close {
    curl -H 'Content-Type: application/json' \
        -d "{\"lock\":\"$2\"}" \
        "http://localhost:8081/stories/$1/close"
}

# Renew story session rights
# $1: story
# $2: lockid
# prints request result
function keepup {
    curl -H 'Content-Type: application/json' \
        -d "{\"lock\":\"$2\"}" \
        "http://localhost:8081/stories/$1/keepup"
}

# Save a story you own the editing rights of
# $1: story
# $2: lockid
# $3: content
# prints request result
function save {
    curl -H 'Content-Type: application/json' \
        -d "{\"lock\":\"$2\", \"file\": \"$3\"}" \
        "http://localhost:8081/stories/$1/save"
}

