package com.chocohead.merger.mappings;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

import matcher.type.FieldInstance;
import matcher.type.MethodInstance;

import com.chocohead.merger.mappings.Tiny2Writer.ClassMappingState.MemberMappingState;

public class Tiny2Writer extends TinyWriter {
	public static class ClassMappingState {
		public static class MemberMappingState {
			public MemberMappingState(String name, String desc, String server, String client) {
				this.name = name;
				this.desc = desc;
				this.server = server;
				this.client = client;
			}

			public final String name, desc;
			public final String server, client;
		}

		public ClassMappingState(String name) {
			this.name = name;
		}

		public void map(String server, String client) {
			this.server = server;
			this.client = client;
		}

		public void mapMethod(String name, String desc, String server, String client) {
			String id = MethodInstance.getId(name, desc);
			assert !methodMap.containsKey(id);

			methodMap.put(id, new MemberMappingState(name, desc, server, client));
		}

		public void mapField(String name, String desc, String server, String client) {
			String id = FieldInstance.getId(name, desc);
			assert !fieldMap.containsKey(id);

			fieldMap.put(id, new MemberMappingState(name, desc, server, client));
		}

		public final String name;
		public String server, client;
		public final Map<String, MemberMappingState> methodMap = new LinkedHashMap<>();
		public final Map<String, MemberMappingState> fieldMap = new LinkedHashMap<>();
	}

	private final Map<String, ClassMappingState> classMap = new LinkedHashMap<>();

	public Tiny2Writer(Writer writer) throws IOException {
		super(writer);
	}

	@Override
	protected void writeHeader() throws IOException {
		writer.write("tiny\t2\t0\t");
		writer.write("glue");
		writer.write('\t');
		writer.write("server");
		writer.write('\t');
		writer.write("client");
		writer.write('\n');
	}

	public ClassMappingState getClass(String name) {
		return classMap.computeIfAbsent(name, ClassMappingState::new);
	}

	@Override
	public void acceptClass(String glue, String server, String client) {
		getClass(glue).map(server, client);
	}

	@Override
	public void acceptMethod(String glueClass, String glueName, String desc, String serverName, String clientName) {
		getClass(glueClass).mapMethod(glueName, desc, serverName, clientName);
	}

	@Override
	public void acceptField(String glueClass, String glueName, String desc, String serverName, String clientName) {
		getClass(glueClass).mapField(glueName, desc, serverName, clientName);
	}

	@Override
	public void close() throws IOException {
		for (ClassMappingState clsState : classMap.values()) {
			writer.write("c\t");
			writer.write(clsState.name);
			writer.write('\t');
			if (clsState.server != null) writer.write(clsState.server);
			writer.write('\t');
			if (clsState.client != null) writer.write(clsState.client);
			writer.write('\n');

			for (MemberMappingState mthState : clsState.methodMap.values()) {
				writer.write("\tm\t");
				writer.write(mthState.desc);
				writer.write('\t');
				writer.write(mthState.name);
				writer.write('\t');
				if (mthState.server != null) writer.write(mthState.server);
				writer.write('\t');
				if (mthState.client != null) writer.write(mthState.client);
				writer.write('\n');
			}

			for (MemberMappingState fldState : clsState.fieldMap.values()) {
				writer.write("\tf\t");
				writer.write(fldState.desc);
				writer.write('\t');
				writer.write(fldState.name);
				writer.write('\t');
				if (fldState.server != null) writer.write(fldState.server);
				writer.write('\t');
				if (fldState.client != null) writer.write(fldState.client);
				writer.write('\n');
			}
		}

		super.close();
	}
}