package dev.jbang.source.generators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.text.StringEscapeUtils;

import dev.jbang.Settings;
import dev.jbang.source.*;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.Util;

public class JshCmdGenerator extends BaseCmdGenerator {
	protected final Project prj;

	public JshCmdGenerator(Project prj, RunContext ctx) {
		super(ctx);
		this.prj = prj;
	}

	@Override
	protected Code getCode() {
		return prj;
	}

	@Override
	protected List<String> generateCommandLineList() throws IOException {
		List<String> fullArgs = new ArrayList<>();

		String classpath = ctx.resolveClassPath(prj).getClassPath();

		List<String> optionalArgs = new ArrayList<>();

		String requestedJavaVersion = ctx.getJavaVersionOr(getCode());
		String javacmd;
		javacmd = JavaUtil.resolveInJavaHome("jshell", requestedJavaVersion);

		if (prj.getJarFile() != null && Files.exists(prj.getJarFile())) {
			if (Util.isBlankString(classpath)) {
				classpath = prj.getJarFile().toAbsolutePath().toString();
			} else {
				classpath = prj.getJarFile().toAbsolutePath() + Settings.CP_SEPARATOR + classpath.trim();
			}
		}

		// NB: See https://github.com/jbangdev/jbang/issues/992 for the reasons why we
		// use the -J flags below

		optionalArgs.add("--execution=local");
		optionalArgs.add("-J--add-modules=ALL-SYSTEM");

		if (!Util.isBlankString(classpath)) {
			optionalArgs.add("--class-path=" + classpath);
			optionalArgs.add("-J--class-path=" + classpath);
		}

		optionalArgs.add("--startup=DEFAULT");

		Path tempFile = Files.createTempFile("jbang_arguments_",
				prj.getResourceRef().getFile().getFileName().toString());

		String defaultImports = "import java.lang.*;\n" +
				"import java.util.*;\n" +
				"import java.io.*;" +
				"import java.net.*;" +
				"import java.math.BigInteger;\n" +
				"import java.math.BigDecimal;\n";
		String mainClass = ctx.getMainClassOr(prj);
		Util.writeString(tempFile,
				defaultImports + generateArgs(ctx.getArguments(), ctx.getProperties()) +
						generateStdInputHelper() +
						generateMain(mainClass));
		if (mainClass != null) {
			if (!mainClass.contains(".")) {
				Util.warnMsg("Main class `" + mainClass
						+ "` is in the default package which JShell unfortunately does not support. You can still use JShell to explore the JDK and any dependencies available on the classpath.");
			} else {
				Util.infoMsg("You can run the main class `" + mainClass + "` using: userMain(args)");
			}
		}
		optionalArgs.add("--startup=" + tempFile.toAbsolutePath());

		if (ctx.isDebugEnabled()) {
			Util.warnMsg("debug not possible when running via jshell.");
		}
		if (ctx.isFlightRecordingEnabled()) {
			Util.warnMsg("Java Flight Recording not possible when running via jshell.");
		}

		fullArgs.add(javacmd);
		addAgentsArgs(fullArgs);

		fullArgs.addAll(jshellOpts(ctx.getRuntimeOptionsMerged(prj)));
		fullArgs.addAll(jshellOpts(ctx.getAutoDetectedModuleArguments(prj, requestedJavaVersion)));
		fullArgs.addAll(optionalArgs);

		if (prj.isJShell() || ctx.getForceType() == Source.Type.jshell) {
			ArrayList<ResourceRef> revSources = new ArrayList<>(prj.getMainSourceSet().getSources());
			Collections.reverse(revSources);
			for (ResourceRef s : revSources) {
				fullArgs.add(s.getFile().toString());
			}
		}

		if (!ctx.isInteractive()) {
			Path exitFile = Files.createTempFile("jbang_exit_",
					prj.getResourceRef().getFile().getFileName().toString());
			Util.writeString(exitFile, "/exit");
			fullArgs.add(exitFile.toString());
		}

		return fullArgs;
	}

	private Collection<String> jshellOpts(List<String> opts) {
		return opts.stream().map(it -> "-J" + it).collect(Collectors.toList());
	}

	public static String generateArgs(List<String> args, Map<String, String> properties) {

		String buf = "String[] args = { " +
				args.stream()
					.map(s -> '"' + StringEscapeUtils.escapeJava(s) + '"')
					.collect(Collectors.joining(", "))
				+
				" }" +
				(properties.isEmpty() ? "" : "\n") +
				properties	.entrySet()
							.stream()
							.map(x -> "System.setProperty(\"" + x.getKey() + "\",\"" + x.getValue() + "\");")
							.collect(Collectors.joining("\n"));
		return buf;
	}

	private static String generateStdInputHelper() {
		String buf = "\nStream<String> lines() { return new BufferedReader(new InputStreamReader(System.in)).lines(); }\n";
		buf += "\nStream<String> lines(String path) throws IOException { return Files.lines(Path.of(path)); }\n";
		buf += "/open PRINTING\n";
		return buf;
	}

	private static String generateMain(String main) {
		if (main != null) {
			return "\nint userMain(String[] args) { return " + main + "(args);}\n";
		}
		return "";
	}
}
