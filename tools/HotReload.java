import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/** Development debugger only; never bundled in the mod. One atomic reload per successful build. */
class HotReload {
    private static Map<String, byte[]> classes(Path root) throws Exception {
        var result = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.walk(root.resolve("toomanyagents"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String name = root.relativize(path).toString().replace(java.io.File.separatorChar, '.');
                result.put(name.substring(0, name.length() - 6), Files.readAllBytes(path));
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1");
        options.get("port").setValue(args[0]);
        options.get("timeout").setValue("10000");
        Path root = Path.of(args[1]);
        var previous = classes(root);
        VirtualMachine vm = connector.attach(options);
        try {
            // Drain debugger lifecycle events even though we never set breakpoints.
            Thread.ofPlatform().daemon().start(() -> {
                try { while (true) vm.eventQueue().remove().resume(); }
                catch (InterruptedException | com.sun.jdi.VMDisconnectedException ignored) {}
            });
            if (!vm.canRedefineClasses()) throw new IllegalStateException("Runtime does not support HotSwap");
            System.out.println("READY");
            var input = new Scanner(System.in);
            while (input.hasNextLine()) {
                if (!input.nextLine().equals("reload")) break;
                try {
                    var next = classes(root);
                    if (!next.keySet().containsAll(previous.keySet()))
                        throw new IllegalStateException("A compiled class was removed; restart to unload it");
                    var definitions = new LinkedHashMap<ReferenceType, byte[]>();
                    int changed = 0;
                    for (var entry : next.entrySet()) {
                        if (Arrays.equals(previous.get(entry.getKey()), entry.getValue())) continue;
                        changed++;
                        for (var type : vm.classesByName(entry.getKey())) definitions.put(type, entry.getValue());
                    }
                    // Redefinition is all-or-nothing; a failed batch leaves loaded definitions intact.
                    if (!definitions.isEmpty()) vm.redefineClasses(definitions);
                    previous = next;
                    System.out.println("RELOADED " + definitions.size() + " loaded classes; " + changed + " changed class files");
                } catch (Exception | LinkageError failure) {
                    System.out.println("RESTART " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
        } finally {
            try { vm.dispose(); } catch (com.sun.jdi.VMDisconnectedException ignored) {}
        }
    }
}
