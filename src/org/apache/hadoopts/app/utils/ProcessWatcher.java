
package org.apache.hadoopts.app.utils;

public class ProcessWatcher implements Runnable {

    private Process p;
    private volatile boolean finished = false;

    public ProcessWatcher(Process p) {
        this.p = p;
        new Thread(this).start();
    }

    public boolean isFinished() {
        return finished;
    }

    public void run() {
        try {
            p.waitFor();
        } catch (Exception e) {}
        finished = true;
    }

}