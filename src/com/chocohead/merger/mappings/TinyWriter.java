package com.chocohead.merger.mappings;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;

public class TinyWriter implements Closeable {
	protected final Writer writer;

	public static TinyWriter normal(Path file) throws IOException {
		return new TinyWriter(Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
	}

	public static TinyWriter compressed(Path file) throws IOException {
		return new TinyWriter(new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)));
	}

	public static TinyWriter v2(Path file) throws IOException {
		return new Tiny2Writer(Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
	}

	public TinyWriter(Writer writer) throws IOException {
		this.writer = writer;

		writeHeader();
	}

	protected void writeHeader() throws IOException {
		writer.write("v1\t");
		writer.write("glue");
		writer.write('\t');
		writer.write("server");
		writer.write('\t');
		writer.write("client");
		writer.write('\n');
	}

	public void acceptClass(String glue, String server, String client) {
		try {
			writer.write("CLASS\t");
			writer.write('\t');
			writer.write(glue);
			writer.write('\t');
			if (server != null) writer.write(server);
			writer.write('\t');
			if (client != null) writer.write(client);
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny class", e);
		}
	}

	public void acceptMethod(String glueClass, String glueName, String desc, String serverName, String clientName) {
		try {
			writer.write("METHOD\t");
			writer.write(glueClass);
			writer.write('\t');
			writer.write(desc);
			writer.write('\t');
			writer.write(glueName);
			writer.write('\t');
			if (serverName != null) writer.write(serverName);
			writer.write('\t');
			if (clientName != null) writer.write(clientName);
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny method", e);
		}
	}

	public void acceptField(String glueClass, String glueName, String desc, String serverName, String clientName) {
		try {
			writer.write("FIELD\t");
			writer.write(glueClass);
			writer.write('\t');
			writer.write(desc);
			writer.write('\t');
			writer.write(glueName);
			writer.write('\t');
			if (serverName != null) writer.write(serverName);
			writer.write('\t');
			if (clientName != null) writer.write(clientName);
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny field", e);
		}
	}

	public void flush() throws IOException {
		if (writer != null) writer.flush();
	}

	@Override
	public void close() throws IOException {
		if (writer != null) writer.close();
	}
}