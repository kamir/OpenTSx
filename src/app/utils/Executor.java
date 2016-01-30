package app.utils;

import java.io.*;

/**
 *
 * @author kamir
 */
public class Executor {
 

  public static String execute(String cmd, int outChannel) throws IOException {

    // A Runtime object has methods for dealing with the OS
    Runtime r = Runtime.getRuntime();
    Process p;     // Process tracks one external native process
    BufferedReader is;  // reader for output of process
    BufferedReader es;  // reader for output of process
    String line;
    
    // Our argv[0] contains the program to run; remaining elements
    // of argv contain args for the target program. This is just
    // what is needed for the String[] form of exec.
    p = r.exec( cmd );

    System.out.println(">>> prepae for run : " + cmd );

    // getInputStream gives an Input stream connected to
    // the process p's standard output. Just use it to make
    // a BufferedReader to readLine() what the program writes out.
    is = new BufferedReader(new InputStreamReader(p.getInputStream()));
    es = new BufferedReader(new InputStreamReader(p.getErrorStream()));

    String ll = null;
    while ((line = is.readLine()) != null)
      ll = line;
    
    System.out.println(">>> Done.");
    System.out.flush();
    try {
      p.waitFor();  // wait for process to complete
    } catch (InterruptedException e) {
      System.err.println(e);  // "Can'tHappen"
      return "ERROR - UNKNOW";
    }
    System.err.println(">>> Process done, exit status was " + p.exitValue());
    return ll;
  }
}
