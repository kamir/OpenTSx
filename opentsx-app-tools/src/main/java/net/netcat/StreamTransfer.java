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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;

/**
 * Stream Transfer Utility: Transfers data between input and output streams.
 *
 * <p>This utility class implements {@link Runnable} to enable asynchronous data transfer
 * between two streams in separate threads. It's commonly used with network sockets to
 * simultaneously handle bidirectional communication.
 *
 * <h2>Use Case:</h2>
 * <p>In network programming, full-duplex communication requires separate threads for
 * reading and writing. This class facilitates that by:
 * <ul>
 *   <li>Reading lines from an input stream</li>
 *   <li>Writing those lines to an output stream</li>
 *   <li>Continuing until the input stream closes</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * Socket socket = new Socket("localhost", 1234);
 *
 * // Transfer from stdin to socket
 * Thread t1 = new Thread(new StreamTransfer(
 *     System.in,
 *     socket.getOutputStream()
 * ));
 *
 * // Transfer from socket to stdout
 * Thread t2 = new Thread(new StreamTransfer(
 *     socket.getInputStream(),
 *     System.out
 * ));
 *
 * t1.start();
 * t2.start();
 * }</pre>
 *
 * <h2>Thread Safety:</h2>
 * <p>Each instance should run in its own thread. Multiple instances can safely
 * operate on different stream pairs concurrently.
 *
 * @author kamir
 * @version 1.0.0
 * @see NetCat4JDataStreamRecorder
 * @see Runnable
 * @since OpenTSx 3.0.0
 */
public class StreamTransfer implements Runnable {
	private InputStream input;
	private OutputStream output;

	public StreamTransfer(InputStream input, OutputStream output) {
		this.input = input;
		this.output = output;
	}

	@Override
	public void run() {
		try {
			PrintWriter writer = new PrintWriter(output);
			BufferedReader reader = new BufferedReader(new InputStreamReader(input));
			String line;
			while ((line = reader.readLine()) != null) {
				writer.println(line);
				writer.flush();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}