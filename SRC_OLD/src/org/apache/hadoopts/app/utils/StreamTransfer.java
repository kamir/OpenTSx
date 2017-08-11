package org.apache.hadoopts.app.utils;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;

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