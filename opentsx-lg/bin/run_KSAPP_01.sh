#!/bin/bash

cd .. || exit 1

java -cp target/opentsx-lg-3.0.1.jar org.opentsx.lg.analyser.FWL_TSA_KStreamsApplication
