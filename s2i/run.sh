#!/bin/bash
# 
# This script is used to update/customize the Docker build 
#
#
set -euo pipefail
dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

while [ $# -gt 0 ]; do
	case $1 in
		--from) 

			from="fabric8/s2i-java:2.0.2"
			from=${2:-$from}			

			echo "Updating Image: FROM $from"
			perl -p -i -e "s|FROM .*|FROM $from|" src/main/docker/Dockerfile
			shift 2 
		;;
		--update-project) 
			ver="0.1-SNAPSHOT"
			ver=${2:-$ver}			

			echo "Updating project using: syndesis-builder-image-generator ver: $ver"			
			mvn dependency:get "-Dartifact=io.syndesis:syndesis-builder-image-generator:$ver" -Ddest=syndesis-builder-image-generator.jar

			echo java -jar target/syndesis-builder-image-generator.jar --to=$dir/src/main/docker/project
			java -jar target/syndesis-builder-image-generator.jar --to=$dir/src/main/docker/project
			shift 2
		;;
		--to) 
			to="syndesis/syndesis-s2i"
			to=${2:-$to}			

			echo "Building Image: $to"
			docker build $dir/src/main/docker -t $to
			shift 2 
		;;
		*)
			echo "Invalid Syntax. Unsupported arg: $1"
			exit 1
		;;
	esac
done



