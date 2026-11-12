package ca.yorku.eecs3311.huntassist.presentation.cli;

import ca.yorku.eecs3311.huntassist.HuntAssistLauncher;
import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import ca.yorku.eecs3311.huntassist.evidence.EvidenceService;
import ca.yorku.eecs3311.huntassist.review.KnowledgeService;
import ca.yorku.eecs3311.huntassist.review.ReviewService;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static picocli.CommandLine.ScopeType.INHERIT;

@Command(name = "huntassist", mixinStandardHelpOptions = true, version = "HuntAssist 0.1.0",
        description = "Local traffic evidence and defensive review.")
public final class HuntAssistCommand implements Runnable {
    @Option(names = "--store", defaultValue = "local-data", scope = INHERIT,
            description = "Local database directory (default: ${DEFAULT-VALUE}).")
    private Path store;

    @Option(names = "--format", defaultValue = "table", scope = INHERIT,
            description = "Output format: table or json.")
    private CliOutput.Format format;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private final HuntAssistLauncher launcher;
    private final CliOutput output;

    private HuntAssistCommand(HuntAssistLauncher launcher, PrintWriter out, PrintWriter err) {
        this.launcher = launcher;
        this.output = new CliOutput(out, err);
    }

    public static int run(HuntAssistLauncher launcher, PrintWriter out, PrintWriter err, String... args) {
        HuntAssistCommand root = new HuntAssistCommand(launcher, out, err);
        CommandLine cli = new CommandLine(root)
                .addSubcommand(new ProjectCommands(root))
                .addSubcommand(new TrafficCommands(root))
                .addSubcommand(new SurfaceCommands(root))
                .addSubcommand(new KnowledgeCommands(root))
                .addSubcommand(new ModuleCommands(root))
                .addSubcommand(new PlanCommands(root))
                .addSubcommand(new CoverageCommands(root))
                .setOut(out).setErr(err)
                .setExpandAtFiles(false)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setParameterExceptionHandler((error, ignored) -> {
                    root.output.error(error.getCommandLine().getCommandSpec().qualifiedName(), root.format, 2,
                            "Invalid command or arguments. Use --help for syntax.");
                    return 2;
                })
                .setExecutionExceptionHandler((error, command, ignored) ->
                        root.reportError(command.getCommandSpec().qualifiedName(), error));
        return cli.execute(args);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Start the local browser interface.")
    int serve(@Option(names = "--port", defaultValue = "8080") int port) {
        if (port < 1 || port > 65535) {
            throw AppException.invalid("Choose a port from 1 to 65535.");
        }
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            stop.countDown();
            try {
                closed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "huntassist-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return launcher.runWithStore(store, true, port, ignored -> {
                output.progress("HuntAssist is listening at http://127.0.0.1:" + port + "/");
                try {
                    stop.await();
                    return 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 130;
                }
            });
        } finally {
            closed.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // The JVM is already executing the shutdown hook.
            }
        }
    }

    @Command(name = "analyze", mixinStandardHelpOptions = true, description = "Summarize defensive controls from imported metadata.")
    int analyze(@Option(names = "--project", required = true) UUID project,
                @Option(names = "--endpoint", required = true) UUID endpoint,
                @Option(names = "--module", defaultValue = "xss") String module) {
        return execute("analyze", ctx -> ctx.getBean(ReviewService.class).analyze(project, endpoint, module));
    }

    @Command(name = "ask", mixinStandardHelpOptions = true, description = "Retrieve local evidence and remediation references for a question.")
    int ask(@Option(names = "--project", required = true) UUID project,
            @Option(names = "--endpoint", required = true) UUID endpoint,
            @Option(names = "--module", defaultValue = "xss") String module,
            @Parameters(paramLabel = "QUESTION") String question) {
        return execute("ask", ctx -> ctx.getBean(ReviewService.class).ask(project, endpoint, module, question));
    }

    @Command(name = "compare", mixinStandardHelpOptions = true, description = "Compare recorded observation counts and statuses between two profiles.")
    int compare(@Option(names = "--project", required = true) UUID project,
                @Option(names = "--first", required = true) String first,
                @Option(names = "--second", required = true) String second) {
        return execute("compare", ctx -> ctx.getBean(EvidenceService.class).compare(project, first, second));
    }

    private int execute(String command, Function<ConfigurableApplicationContext, ?> action) {
        Thread caller = Thread.currentThread();
        CountDownLatch closed = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            caller.interrupt();
            try {
                closed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "huntassist-cancellation");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            Object result = launcher.runWithStore(store, false, 0, action);
            if (caller.isInterrupted()) {
                return reportError(command, new AppException(AppException.Code.CANCELLED, "Operation interrupted."));
            }
            output.success(command, format, result);
            return 0;
        } catch (RuntimeException e) {
            if (caller.isInterrupted()) {
                return reportError(command, new AppException(AppException.Code.CANCELLED, "Operation interrupted."));
            }
            return reportError(command, e);
        } finally {
            closed.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // The JVM is already executing the shutdown hook.
            }
        }
    }

    private int reportError(String command, Throwable error) {
        Throwable cause = error;
        while (!(cause instanceof AppException) && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof AppException failure) {
            int exit = switch (failure.code()) {
                case INVALID -> 2;
                case NOT_FOUND -> 3;
                case REFUSED -> 4;
                case UNAVAILABLE -> 5;
                case CONFLICT -> 6;
                case FAILED -> 7;
                case INTERNAL -> 10;
                case CANCELLED -> 130;
            };
            output.error(command, format, exit, failure.getMessage());
            return exit;
        }
        output.error(command, format, 10, "HuntAssist could not complete the operation.");
        return 10;
    }

    private static Object readFile(Path path, Function<InputStream, ?> action) {
        if (!Files.isRegularFile(path)) {
            throw AppException.invalid("Choose an existing regular input file.");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return action.apply(input);
        } catch (IOException e) {
            throw AppException.invalid("The input file could not be read.");
        }
    }

    private abstract static class Group implements Runnable {
        final HuntAssistCommand root;
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;

        Group(HuntAssistCommand root) { this.root = root; }
        public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
    }

    @Command(name = "project", mixinStandardHelpOptions = true, description = "Manage local project scope.")
    private static final class ProjectCommands extends Group {
        ProjectCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "create", mixinStandardHelpOptions = true)
        int create(@Option(names = "--name", required = true) String name,
                   @Option(names = "--include", required = true) List<String> includes,
                   @Option(names = "--exclude") List<String> excludes,
                   @Option(names = "--restrictions", defaultValue = "") String restrictions) {
            return root.execute("project create", ctx -> ctx.getBean(ProjectService.class)
                    .create(name, includes, excludes == null ? List.of() : excludes, restrictions));
        }

        @Command(name = "list", mixinStandardHelpOptions = true)
        int list() {
            return root.execute("project list", ctx -> ctx.getBean(ProjectService.class).list());
        }

        @Command(name = "scope", mixinStandardHelpOptions = true)
        int scope(@Option(names = "--project", required = true) UUID project,
                  @Parameters(paramLabel = "HOST") String host) {
            return root.execute("project scope", ctx -> ctx.getBean(ProjectService.class).classify(project, host));
        }
    }

    @Command(name = "traffic", mixinStandardHelpOptions = true, description = "Import and audit local capture metadata.")
    private static final class TrafficCommands extends Group {
        TrafficCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "import", mixinStandardHelpOptions = true)
        int importTraffic(@Option(names = "--project", required = true) UUID project,
                          @Option(names = "--profile", defaultValue = "default") String profile,
                          @Parameters(paramLabel = "FILE") Path path) {
            return root.execute("traffic import", ctx -> {
                root.output.progress("Reading the local capture.");
                return readFile(path, input -> ctx.getBean(EvidenceService.class).importTraffic(project, input, profile));
            });
        }

        @Command(name = "history", mixinStandardHelpOptions = true)
        int history(@Option(names = "--project", required = true) UUID project) {
            return root.execute("traffic history", ctx -> ctx.getBean(EvidenceService.class).imports(project));
        }
    }

    @Command(name = "surface", mixinStandardHelpOptions = true, description = "Browse sanitized capture metadata.")
    private static final class SurfaceCommands extends Group {
        SurfaceCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "list", mixinStandardHelpOptions = true)
        int list(@Option(names = "--project", required = true) UUID project,
                 @Option(names = "--filter", defaultValue = "") String filter) {
            String query = filter.strip().toLowerCase(Locale.ROOT);
            return root.execute("surface list", ctx -> ctx.getBean(EvidenceService.class).inventory(project).stream()
                    .filter(e -> (e.host() + " " + e.method() + " " + e.path()).toLowerCase(Locale.ROOT).contains(query))
                    .toList());
        }

        @Command(name = "evidence", mixinStandardHelpOptions = true)
        int evidence(@Option(names = "--project", required = true) UUID project,
                     @Option(names = "--endpoint", required = true) UUID endpoint) {
            return root.execute("surface evidence", ctx -> ctx.getBean(EvidenceService.class).evidence(project, endpoint));
        }
    }

    @Command(name = "knowledge", mixinStandardHelpOptions = true, description = "Import and find defensive reference notes.")
    private static final class KnowledgeCommands extends Group {
        KnowledgeCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "import", mixinStandardHelpOptions = true)
        int importNote(@Option(names = "--project", required = true) UUID project,
                       @Parameters(paramLabel = "FILE") Path path) {
            return root.execute("knowledge import", ctx -> readFile(path,
                    input -> ctx.getBean(KnowledgeService.class).importNote(project, path.getFileName().toString(), input)));
        }

        @Command(name = "list", mixinStandardHelpOptions = true)
        int list(@Option(names = "--project", required = true) UUID project) {
            return root.execute("knowledge list", ctx -> ctx.getBean(KnowledgeService.class).list(project));
        }

        @Command(name = "search", mixinStandardHelpOptions = true)
        int search(@Option(names = "--project", required = true) UUID project,
                   @Parameters(paramLabel = "QUERY") String query) {
            return root.execute("knowledge search", ctx -> ctx.getBean(KnowledgeService.class).search(project, query));
        }
    }

    @Command(name = "modules", mixinStandardHelpOptions = true, description = "Inspect installed defensive review modules.")
    private static final class ModuleCommands extends Group {
        ModuleCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "list", mixinStandardHelpOptions = true)
        int list() {
            return root.execute("modules list", ctx -> ctx.getBean(ReviewService.class).modules());
        }
    }

    @Command(name = "plan", mixinStandardHelpOptions = true, description = "Generate and audit remediation reviews.")
    private static final class PlanCommands extends Group {
        PlanCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "generate", mixinStandardHelpOptions = true)
        int generate(@Option(names = "--project", required = true) UUID project,
                     @Option(names = "--endpoint", required = true) UUID endpoint,
                     @Option(names = "--module", defaultValue = "xss") String module) {
            return root.execute("plan generate", ctx -> {
                root.output.progress("Preparing remediation review.");
                return ctx.getBean(ReviewService.class).plan(project, endpoint, module);
            });
        }

        @Command(name = "history", mixinStandardHelpOptions = true)
        int history(@Option(names = "--project", required = true) UUID project) {
            return root.execute("plan history", ctx -> ctx.getBean(ReviewService.class).runs(project));
        }
    }

    @Command(name = "coverage", mixinStandardHelpOptions = true, description = "Track defensive review decisions.")
    private static final class CoverageCommands extends Group {
        CoverageCommands(HuntAssistCommand root) { super(root); }

        @Command(name = "list", mixinStandardHelpOptions = true)
        int list(@Option(names = "--project", required = true) UUID project) {
            return root.execute("coverage list", ctx -> ctx.getBean(ReviewService.class).coverage(project));
        }

        @Command(name = "record", mixinStandardHelpOptions = true)
        int record(@Option(names = "--project", required = true) UUID project,
                   @Option(names = "--endpoint", required = true) UUID endpoint,
                   @Option(names = "--module", defaultValue = "xss") String module,
                   @Option(names = "--state", required = true) String state,
                   @Option(names = "--note", defaultValue = "") String note) {
            return root.execute("coverage record", ctx -> ctx.getBean(ReviewService.class)
                    .recordCoverage(project, endpoint, module, state, note));
        }
    }
}
