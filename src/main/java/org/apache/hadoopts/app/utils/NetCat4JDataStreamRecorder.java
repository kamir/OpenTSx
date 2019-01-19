package org.apache.hadoopts.app.utils;

import org.apache.hadoopts.data.generator.Generator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;


/**
 * From Wikipedia: https://de.wikipedia.org/wiki/Netcat
 * 
 * Netcat, auch nc genannt, ist ein einfaches Werkzeug, um Daten von der 
 * Standardein- oder -ausgabe über Netzwerkverbindungen zu transportieren. 
 * 
 * Es arbeitet als Server oder Client mit den Protokollen TCP und UDP. 
 * Die Manpage bezeichnet es als TCP/IP swiss army knife (Schweizer 
 * Taschenmesser für TCP/IP).
 * 
 * Das ursprüngliche Programm wurde 1996 von einer unbekannten Person mit 
 * dem Pseudonym Hobbit für die UNIX-Plattform geschrieben und ist inzwischen 
 * auf praktisch alle Plattformen portiert worden.
 */

/**
 * The NetCat Generator waits for kv pairs on a single web-port.
 * 
 * key:   sensor.metric
 * value: measured value
 * 
 * Time stamp is implicit - defined on the Spark Node.
 * 
 * @author kamir
 */
public class NetCat4J extends Generator {
    
    public String toString() {
        return "mode: " + mode;
    }

    // configuration arguments
    String[] a = null;
    
    // by default we have client.
    String mode = "unknonw";
    
    /**
     * Run the NetCat-tool on $port$.
     * 
     * @param port 
     */
    public NetCat4J(int port) {
        String[] a = {"-p", ""+port};
    }

    static public NetCat4J getNCGServer(int port) {
        String[] a = {"-l", ""+port };
        NetCat4J n = new NetCat4J(port);
        n.a = a;
        n.mode = "server";
        return n;
    }

    /**
     * This NetCat client listens on $port$ on
     * the local IP.
     * 
     * @param port
     * @return 
     */
    static public NetCat4J getNCGClient(int port) {
        
        String[] a = {"-p", ""+port, "127.0.0.1" };
        NetCat4J n = new NetCat4J(port);
        
        n.a = a;
        n.mode = "client";
        
        return n;
    }


    
    @Override
    public void run() {
        try {
            super.run(); 
            
            CommandLineParser parser = new PosixParser();
            
            Options options = new Options();
            options.addOption("l", "listen", false, "listen mode");
            options.addOption("p", "port", true, "port number");
            
            CommandLine line = parser.parse(options, a);
            
            if (line.hasOption('l')) {
                if (line.hasOption('p')) {
                    int port = Integer.parseInt(line.getOptionValue('p'));
                    listen(port);
                }
            } else {
                if (line.hasOption('p')) {
                    int port = Integer.parseInt(line.getOptionValue('p'));
                    connect(line.getArgs()[0], port);
                } else {
                    HelpFormatter formatter = new HelpFormatter();
                    formatter.printHelp("netcat [OPTIONS] <HOST>", options);
                }
            }
        } catch (ParseException ex) {
            Logger.getLogger(NetCat4J.class.getName()).log(Level.SEVERE, null, ex);
        } catch (Exception ex) {
            Logger.getLogger(NetCat4J.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    
    }
    
    
    

	public static void main(String[] _args) throws Exception {
            
          /**
           * Here we test the Client side of the NetCat tool to take
           * data from a server running somewhere else.
           */  
            
          // to test this we need to run "nc -l 1234" on the local machine   
          NetCat4J ncgC = NetCat4J.getNCGClient(1234);
          ncgC.run();
          // all things entered in the nc tool will be printed by the ncgC tool.
          
          
                
	}
        
        

	private static void connect(String host, int port) throws Exception {
            
		System.err.println("Connecting to " + host + " port " + port);
		final Socket socket = new Socket(host, port);
		transferStreams(socket);
	
        }

	private static void listen(int port) throws Exception {
		System.err.println("Listening at port " + port);
		ServerSocket serverSocket = new ServerSocket(port);
		Socket socket = serverSocket.accept();
		System.err.println("Accepted");
		transferStreams(socket);
	}

	private static void transferStreams(Socket socket) throws IOException,
			InterruptedException {
            
		InputStream input1 = System.in;
	
                OutputStream output1 = socket.getOutputStream();
		
                InputStream input2 = socket.getInputStream();
                
                // WE WRITE IN A ChartUpdateOutputStream ...
		PrintStream output2 = System.err;
		
                Thread thread1 = new Thread(new StreamTransfer(input1, output1));
		Thread thread2 = new Thread(new StreamTransfer(input2, output2));
		thread1.start();
		thread2.start();
		thread1.join();
		socket.shutdownOutput();
		thread2.join();
                
                
	}
}
    

