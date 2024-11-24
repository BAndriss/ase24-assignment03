// For solving this task I used GitHub Copilot
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Fuzzer {
    
    public static final Pattern HTML_REGEX = Pattern.compile("<(\\w+)([^>]*)>(.*?)</\\1>");

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '%s'.".formatted(commandToFuzz));
        }

        String seedInput = "<html a=\"value\">...</html>";
        Matcher matcher = HTML_REGEX.matcher(seedInput);
        if (!matcher.matches()) {
            throw new RuntimeException("Seed input does not match HTML regex.");
        }
        String tagName = matcher.group(1);
        String attributes = matcher.group(2);
        String content = matcher.group(3);
        System.out.println("tagName: " + tagName);
        System.out.println("attributes: " + attributes);
        System.out.println("content: " + content);
        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());


        List<Function<String, String>> mutators = new ArrayList<>(List.of(
            input -> input.replace("<html", "a"), // this is just a placeholder, mutators should not only do hard-coded string replacement
            input -> input.replace("<html", ""),
            input -> input.replace("<html", "a").replace(">", ""),
            input -> input.replace("<html", "a").replace(">", "").replace("...", "a"),
            input -> input.replace("<html", "a").replace(">", "").replace("...", "a").replace("</html>", ""),
            input -> input.replace("<html", "<Aéada#>"),

            input -> input.replace(tagName, repatingString(tagName, 10)),// Output: Error: Opening tag name is longer than 16 characters 
            input -> input.replace(attributes, repatingString(attributes, 100)),
            input -> input.replace(content, repatingString(content, 100)),//Output: Error: Content between tags exceeds 64 characters (no closing tag found)
            input -> input.replace(seedInput, repatingString(seedInput, 100)),

            input -> input.replace("<", repatingString("<", 10)),//Output: Error: Tag starts with more than one '<'
            input -> input.replace(">", repatingString(">", 10))//Output: Error: Opening tag name is longer than 16 characters

        ));

        for (int i = 0; i < 10; i++) {
            mutators.add(input -> input.replace(tagName, insertingRandomCharacter(tagName, true)));
            mutators.add(input -> input.replace(attributes, insertingRandomCharacter(attributes, true)));
            mutators.add(input -> input.replace(content, insertingRandomCharacter(content, true)));
            mutators.add(input -> input.replace(seedInput, insertingRandomCharacter(seedInput, true)));
        }

        for (int i = 0; i < 10; i++) {
            mutators.add(input -> input.replace(tagName, changeNRandomCharacter(tagName, 10)));
            mutators.add(input -> input.replace(attributes, changeNRandomCharacter(attributes, 10)));
            mutators.add(input -> input.replace(content, changeNRandomCharacter(content, 10)));
            mutators.add(input -> input.replace(seedInput, changeNRandomCharacter(seedInput, 10)));
        }

        for (int i = 0; i < 10; i++) {
            mutators.add(input -> input.replace(tagName, deleteRandomCharacter(tagName, 1)));
            mutators.add(input -> input.replace(attributes, deleteRandomCharacter(attributes, 1)));
            mutators.add(input -> input.replace(content, deleteRandomCharacter(content, 1)));
            mutators.add(input -> input.replace(seedInput, deleteRandomCharacter(seedInput, 1)));
        }
        

        boolean notZeroExitCode = runCommand(builder, seedInput, getMutatedInputs(seedInput, mutators));
        //For testing the GitHub Actions
        // List<Function<String, String>> mutators2 = new ArrayList<>(List.of(
        //     input -> input

        // ));
        // boolean notZeroExitCode = runCommand(builder, seedInput, getMutatedInputs(seedInput, mutators2));
        if (notZeroExitCode) {
            System.err.println("At least one input caused a non-zero exit code.");
            System.exit(1);
        }
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private static boolean runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        final AtomicBoolean notZeroExitCode = new AtomicBoolean(false);
        Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).forEach(
                input -> { 
                    try {
                        Process process = builder.start();
                        OutputStream stdin = process.getOutputStream();
                        InputStream stdout = process.getInputStream();
                        stdin.write(input.getBytes());
                        stdin.flush();
                        stdin.close();
                        int exitCode = process.waitFor();
                        if (exitCode != 0) {
                            notZeroExitCode.set(true);
                            //For faster debugging only print the first non-zero exit code
                            System.out.printf("Exit code: %s\n", exitCode);
                            String output = readStreamIntoString(stdout);
                            System.out.printf("Input: %s\nOutput: %s\n", input, output);
                        }
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
        );
        return notZeroExitCode.get();
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        return mutators.stream()
                .map(mutator -> mutator.apply(seedInput))
                .collect(Collectors.toList());
    }


    private static String repatingString(String str, int n) {
        // Repating a string n times
        return new String(new char[n]).replace("\0", str);
    }

    private static String insertingRandomCharacter(String str, boolean specialChar){
        // Inserting a random character at a random index
        Random random = new Random();
        int randomIndex = random.nextInt(str.length() + 1);
        char randomChar = 'a';
        int bound = 26;
        if (specialChar){
            randomChar = 0;
            bound = 127;
        }
        if (random.nextBoolean()) {
            randomChar = (char) ('a' + random.nextInt(bound)); // Generates a random lowercase letter
        } else {
            randomChar = (char) ('A' + random.nextInt(bound)); // Generates a random uppercase letter
        }        
        StringBuilder stringBuilder = new StringBuilder(str);
        stringBuilder.insert(randomIndex, randomChar);
    
        return stringBuilder.toString();
     
    }

    private static String changeNRandomCharacter(String str, int n){
        // Changing a random character in the string
        Random random = new Random();
        char randomChar = 'a';
        StringBuilder stringBuilder = new StringBuilder(str);
        for (int i= 0; i< n; i++){
            int randomIndex = random.nextInt(str.length());
            if (random.nextBoolean()) {
                randomChar = (char) ('a' + random.nextInt(26)); // Generates a random lowercase letter
            } else {
                randomChar = (char) ('A' + random.nextInt(26)); // Generates a random uppercase letter
            }        
            stringBuilder.setCharAt(randomIndex, randomChar);
        }
        return stringBuilder.toString();
    }

    private static String deleteRandomCharacter(String str, int n){
        if (str == null || str.length() == 0) {
            return str;
        }
        if (str.length() <= n) {
            return "";
        }

        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder(str);
        for (int i = 0; i < n; i++) {
            int randomIndex = random.nextInt(stringBuilder.length());
            stringBuilder.deleteCharAt(randomIndex);
        }
        return stringBuilder.toString();
    }
}
