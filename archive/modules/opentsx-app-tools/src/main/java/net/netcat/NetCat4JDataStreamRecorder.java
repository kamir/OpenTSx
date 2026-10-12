/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package net.netcat;

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
 * NetCat for Java: A lightweight data stream recorder and network utility.
 *
 * <p>This is a Java implementation of the classic Unix {@code netcat} tool, which serves
 * as a "TCP/IP Swiss Army knife" for reading and writing data across network connections.
 * It can operate in both server (listen) and client (connect) modes using TCP.
 *
 * <h2>About NetCat:</h2>
 * <p>From Wikipedia: Netcat (also known as {@code nc}) is a simple tool for transporting
 * data from standard input/output over network connections. It works as a server or client
 * with TCP and UDP protocols.
 *
 * <p>The original program was written in 1996 by someone with the pseudonym "Hobbit" for
 * the UNIX platform and has since been ported to virtually all platforms.
 *
 * <h2>Primary Use Case:</h2>
 * <p>This implementation is designed as a data stream recorder that receives key-value
 * pairs over a network port for time series data ingestion:
 * <ul>
 *   <li><b>Key:</b> sensor.metric identifier</li>
 *   <li><b>Value:</b> measured value</li>
 *   <li><b>Timestamp:</b> implicit (assigned by receiving node)</li>
 * </ul>
 *
 * <h2>Usage Examples:</h2>
 *
 * <h3>Server Mode (Listen):</h3>
 * <pre>{@code
 * // Create a server listening on port 1234
 * NetCat4JDataStreamRecorder server = NetCat4JDataStreamRecorder.getNCGServer(1234);
 * new Thread(server).start();
 *
 * // Data sent to port 1234 will be printed to console
 * }</pre>
 *
 * <h3>Client Mode (Connect):</h3>
 * <pre>{@code
 * // Connect to a server on localhost:1234
 * NetCat4JDataStreamRecorder client = NetCat4JDataStreamRecorder.getNCGClient(1234);
 * new Thread(client).start();
 *
 * // Reads from stdin and sends to server
 * }</pre>
 *
 * <h3>Command-Line Usage:</h3>
 * <pre>
 * # Listen mode
 * java -cp application-tools.jar net.netcat.NetCat4JDataStreamRecorder -l -p 1234
 *
 * # Client mode
 * java -cp application-tools.jar net.netcat.NetCat4JDataStreamRecorder -p 1234 localhost
 * </pre>
 *
 * <h2>Integration with OpenTSx:</h2>
 * <p>This tool can be used to:
 * <ul>
 *   <li>Ingest streaming sensor data into OpenTSx pipelines</li>
 *   <li>Debug network connections in distributed clusters</li>
 *   <li>Test data flow between cluster nodes</li>
 *   <li>Record time series observations from external sources</li>
 * </ul>
 *
 * @author kamir
 * @version 1.0.0
 * @see StreamTransfer
 * @see Socket
 * @see ServerSocket
 * @since OpenTSx 3.0.0
 */
public class NetCat4JDataStreamRecorder implements Runnable {

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
    public NetCat4JDataStreamRecorder(int port) {
        String[] a = {"-p", ""+port};
    }

    static public NetCat4JDataStreamRecorder getNCGServer(int port) {
        String[] a = {"-l", ""+port };
        NetCat4JDataStreamRecorder n = new NetCat4JDataStreamRecorder(port);
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
    static public NetCat4JDataStreamRecorder getNCGClient(int port) {
        
        String[] a = {"-p", ""+port, "127.0.0.1" };
        NetCat4JDataStreamRecorder n = new NetCat4JDataStreamRecorder(port);
        
        n.a = a;
        n.mode = "client";
        
        return n;
    }


    
    @Override
    public void run() {
        try {

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
            Logger.getLogger(NetCat4JDataStreamRecorder.class.getName()).log(Level.SEVERE, null, ex);
        } catch (Exception ex) {
            Logger.getLogger(NetCat4JDataStreamRecorder.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    
    }
    
    
    

	public static void main(String[] _args) throws Exception {
            
          /**
           * Here we test the Client side of the NetCat tool to take
           * data from a server running somewhere else.
           */  
            
          // to test this we need to run "nc -l 1234" on the local machine   
          NetCat4JDataStreamRecorder ncgC = NetCat4JDataStreamRecorder.getNCGClient(1234);
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
    

