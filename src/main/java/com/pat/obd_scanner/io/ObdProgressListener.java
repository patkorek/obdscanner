package com.pat.obd_scanner.io;

public interface ObdProgressListener {

    void stateUpdate(final ObdCommandJob job);

}