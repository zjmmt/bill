import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InternalSigning {
    private static final String KEY_ALIAS = "bill-internal-test";
    private static final String KEYSTORE_FILE_NAME = "bill-internal-test.keystore";
    private static final String KEY_PASSWORD_ENV = "BILL_INTERNAL_SIGNING_PASSWORD";
    private static final int MINIMUM_PASSWORD_LENGTH = 6;
    private static final int MAXIMUM_PASSWORD_LENGTH = 256;
    private static final List<String> RELEASE_APK_PATHS = List.of(
        "app/build/outputs/apk/release/app-arm64-v8a-release.apk",
        "app/build/outputs/apk/release/app-x86_64-release.apk",
        "app/build/outputs/apk/release/Bill-Local-Ledger-Android-arm64-v8a.apk",
        "app/build/outputs/apk/release/Bill-Local-Ledger-Android-x86_64.apk"
    );

    private final Path projectRoot;
    private final Path javaExecutable;
    private final Path keytool;
    private final Path releaseScript;
    private final Path certificatePinFile;
    private final Path defaultKeystore;

    private InternalSigning(Path projectRoot) throws IOException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.javaExecutable = this.projectRoot.resolve(".tools/jdk17/bin/java.exe");
        this.keytool = this.projectRoot.resolve(".tools/jdk17/bin/keytool.exe");
        this.releaseScript = this.projectRoot.resolve("scripts/release.cmd");
        this.certificatePinFile = this.projectRoot.resolve("scripts/internal-signing-certificate.sha256");
        this.defaultKeystore = this.projectRoot
            .resolveSibling("Bill-signing")
            .resolve(KEYSTORE_FILE_NAME)
            .toAbsolutePath()
            .normalize();
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            InternalSigning signing = new InternalSigning(Path.of("."));
            exitCode = signing.run(args);
        } catch (Exception exception) {
            System.err.println("Internal signing could not start: " + safeMessage(exception));
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private int run(String[] args) throws Exception {
        if (args.length != 1) {
            printUsage();
            return 2;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> createKey();
            case "build" -> buildRelease();
            case "status" -> showStatus();
            case "self-test" -> selfTest();
            case "verify-release" -> verifyReleaseArtifacts();
            default -> {
                printUsage();
                yield 2;
            }
        };
    }

    private int createKey() throws Exception {
        requireTooling();
        Console console = requireConsole();
        if (Files.exists(defaultKeystore)) {
            throw new IllegalStateException(
                "The fixed internal-test keystore already exists. Refusing to overwrite it."
            );
        }
        if (readPinnedCertificateSha256(false) != null) {
            throw new IllegalStateException(
                "This repository already pins the internal-test certificate. Restore the existing keystore from backup instead of creating a replacement."
            );
        }
        Path keyStore = prepareNewExternalKeystore();
        char[] password = readNewPassword(console);
        String passwordValue = new String(password);
        boolean keepKeystore = false;
        try {
            List<String> command = new ArrayList<>();
            command.add(keytool.toString());
            command.addAll(List.of(
                "-genkeypair",
                "-alias", KEY_ALIAS,
                "-keyalg", "RSA",
                "-keysize", "3072",
                "-sigalg", "SHA256withRSA",
                "-validity", "9125",
                "-dname", "CN=Bill Internal Test,O=Bill",
                "-storetype", "PKCS12",
                "-keystore", keyStore.toString(),
                "-storepass:env", KEY_PASSWORD_ENV,
                "-keypass:env", KEY_PASSWORD_ENV,
                "-noprompt"
            ));
            if (runWithSecret(command, KEY_PASSWORD_ENV, passwordValue, false) != 0) {
                System.err.println("The internal-test keystore was not created.");
                return 1;
            }
            String certificateSha256 = readKeystoreCertificateSha256(keyStore, passwordValue);
            if (certificateSha256 == null) {
                System.err.println("The new keystore could not be verified and will not be kept.");
                return 1;
            }
            keepKeystore = true;
            System.out.println();
            System.out.println("Internal-test keystore created outside the repository:");
            System.out.println(keyStore);
            System.out.println("Public certificate SHA-256: " + certificateSha256);
            System.out.println("Pin that public fingerprint before the first distributed build.");
            System.out.println("Back up this exact file before distributing a signed APK.");
            System.out.println("Keep the backup and password separate; losing either prevents future update installs.");
            return 0;
        } finally {
            Arrays.fill(password, '\0');
            passwordValue = null;
            if (!keepKeystore) {
                Files.deleteIfExists(keyStore);
            }
        }
    }

    private int buildRelease() throws Exception {
        requireTooling();
        Path keyStore = requireExistingExternalKeystore();
        String expectedCertificateSha256 = readPinnedCertificateSha256(true);
        Console console = requireConsole();
        String versionCode = console.readLine("Version code (positive integer): ");
        String versionName = console.readLine("Version name (for example 0.1.0-test.1): ");
        validateVersion(versionCode, versionName);

        char[] password = readExistingPassword(console);
        String passwordValue = new String(password);
        try {
            String actualCertificateSha256 = readKeystoreCertificateSha256(keyStore, passwordValue);
            if (actualCertificateSha256 == null) {
                System.err.println("The keystore password was rejected or the signing entry is invalid.");
                return 1;
            }
            if (!MessageDigest.isEqual(
                expectedCertificateSha256.getBytes(StandardCharsets.US_ASCII),
                actualCertificateSha256.getBytes(StandardCharsets.US_ASCII)
            )) {
                System.err.println(
                    "The keystore certificate does not match the repository pin. Restore the fixed internal-test keystore from backup."
                );
                return 1;
            }

            ProcessBuilder processBuilder = newReleaseProcess();
            processBuilder.inheritIO();
            Map<String, String> environment = processBuilder.environment();
            clearSigningEnvironment(environment);
            environment.put("BILL_VERSION_CODE", versionCode);
            environment.put("BILL_VERSION_NAME", versionName);
            environment.put("BILL_RELEASE_STORE_FILE", keyStore.toString());
            environment.put("BILL_RELEASE_STORE_PASSWORD", passwordValue);
            environment.put("BILL_RELEASE_KEY_ALIAS", KEY_ALIAS);
            environment.put("BILL_RELEASE_KEY_PASSWORD", passwordValue);
            environment.put("BILL_REQUIRE_SIGNED_RELEASE", "true");

            Process process;
            try {
                process = processBuilder.start();
            } finally {
                clearSigningEnvironment(environment);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("Signed release build failed with exit code " + exitCode + ".");
                return exitCode;
            }
            System.out.println("Signed internal-test release completed.");
            return 0;
        } finally {
            Arrays.fill(password, '\0');
            passwordValue = null;
        }
    }

    private int showStatus() throws IOException {
        Path normalized = defaultKeystore.toAbsolutePath().normalize();
        requireOutsideRepository(normalized);
        System.out.println("Internal-test keystore path:");
        System.out.println(normalized);
        System.out.println(Files.isRegularFile(normalized) ? "Status: present" : "Status: not created");
        return 0;
    }

    private int selfTest() throws Exception {
        requireTooling();
        requireOutsideRepository(defaultKeystore);
        readPinnedCertificateSha256(true);
        validateVersion("1", "0.1.0-test.1");
        expectInvalidVersion("0", "0.1.0");
        expectInvalidVersion("abc", "0.1.0");
        expectInvalidVersion("1", " ");
        expectInvalidVersion("1", "bad\nname");
        ProcessBuilder releaseProbe = newReleaseProcess();
        clearSigningEnvironment(releaseProbe.environment());
        releaseProbe.redirectInput(ProcessBuilder.Redirect.INHERIT);
        releaseProbe.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        releaseProbe.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (releaseProbe.start().waitFor() != 2) {
            throw new IllegalStateException("Release preflight did not fail closed without signing input.");
        }
        if (!KEY_ALIAS.equals("bill-internal-test")) {
            throw new IllegalStateException("Unexpected internal signing alias.");
        }
        System.out.println("Internal signing self-test passed.");
        System.out.println("No password was requested and no keystore was created.");
        return 0;
    }

    private int verifyReleaseArtifacts() throws Exception {
        requireTooling();
        String expectedCertificateSha256 = readPinnedCertificateSha256(true);
        Path apkSignerJar = findApkSignerJar();
        for (String relativePath : RELEASE_APK_PATHS) {
            Path apk = projectRoot.resolve(relativePath).normalize();
            if (!Files.isRegularFile(apk)) {
                System.err.println("Signed release APK is missing: " + relativePath);
                return 1;
            }
            if (!verifyApkIdentity(apkSignerJar, apk, expectedCertificateSha256)) {
                System.err.println(
                    "Signed release APK does not match the pinned internal-test identity: " + relativePath
                );
                return 1;
            }
        }
        System.out.println("Signed release APK identities match the repository certificate pin.");
        return 0;
    }

    private Path prepareNewExternalKeystore() throws IOException {
        requireOutsideRepository(defaultKeystore);
        if (Files.exists(defaultKeystore)) {
            throw new IllegalStateException(
                "The fixed internal-test keystore already exists. Refusing to overwrite it."
            );
        }
        Path parent = defaultKeystore.getParent();
        Files.createDirectories(parent);
        Path resolved = parent.toRealPath().resolve(defaultKeystore.getFileName()).normalize();
        requireOutsideRepository(resolved);
        if (Files.exists(resolved)) {
            throw new IllegalStateException(
                "The fixed internal-test keystore already exists. Refusing to overwrite it."
            );
        }
        return resolved;
    }

    private Path requireExistingExternalKeystore() throws IOException {
        requireOutsideRepository(defaultKeystore);
        if (!Files.isRegularFile(defaultKeystore)) {
            throw new IllegalStateException(
                "The fixed internal-test keystore does not exist. Run the create command first."
            );
        }
        Path resolved = defaultKeystore.toRealPath();
        requireOutsideRepository(resolved);
        return resolved;
    }

    private void requireOutsideRepository(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        String rootText = normalizeForComparison(projectRoot);
        String candidateText = normalizeForComparison(normalized);
        String rootPrefix = rootText.endsWith("\\") ? rootText : rootText + "\\";
        if (candidateText.equals(rootText) || candidateText.startsWith(rootPrefix)) {
            throw new IllegalArgumentException("The signing keystore must stay outside the repository.");
        }
    }

    private static String normalizeForComparison(Path path) {
        String text = path.toString().replace('/', '\\');
        return isWindows() ? text.toLowerCase(Locale.ROOT) : text;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void requireTooling() {
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IllegalStateException("Project JDK java is missing at " + javaExecutable + ".");
        }
        if (!Files.isRegularFile(keytool)) {
            throw new IllegalStateException("Project JDK keytool is missing at " + keytool + ".");
        }
        if (!Files.isRegularFile(releaseScript)) {
            throw new IllegalStateException("Release script is missing at " + releaseScript + ".");
        }
    }

    private Path findApkSignerJar() throws IOException {
        Path buildTools = projectRoot.resolve(".android-sdk/build-tools");
        if (!Files.isDirectory(buildTools)) {
            throw new IllegalStateException("Project Android build-tools directory is missing at " + buildTools + ".");
        }
        try (var candidates = Files.list(buildTools)) {
            return candidates
                .filter(Files::isDirectory)
                .map(candidate -> candidate.resolve("lib/apksigner.jar"))
                .filter(Files::isRegularFile)
                .max((left, right) -> compareVersionNames(buildToolVersion(left), buildToolVersion(right)))
                .orElseThrow(() -> new IllegalStateException("Project Android apksigner.jar is missing."));
        }
    }

    private static String buildToolVersion(Path apkSignerJar) {
        return apkSignerJar.getParent().getParent().getFileName().toString();
    }

    private static int compareVersionNames(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < leftParts.length ? leadingInteger(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? leadingInteger(rightParts[index]) : 0;
            int comparison = Integer.compare(leftValue, rightValue);
            if (comparison != 0) {
                return comparison;
            }
        }
        return left.compareTo(right);
    }

    private static int leadingInteger(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean verifyApkIdentity(
        Path apkSignerJar,
        Path apk,
        String expectedCertificateSha256
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
            javaExecutable.toString(),
            "-jar",
            apkSignerJar.toString(),
            "verify",
            "--verbose",
            "--print-certs",
            apk.toString()
        );
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);
        clearSigningEnvironment(processBuilder.environment());
        Process process = processBuilder.start();
        boolean hasSingleSigner = false;
        boolean hasExpectedCertificate = false;
        try (
            BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )
        ) {
            String line;
            while ((line = output.readLine()) != null) {
                String normalized = line.trim();
                if (normalized.equals("Number of signers: 1")) {
                    hasSingleSigner = true;
                }
                if (
                    normalized.equalsIgnoreCase(
                        "Signer #1 certificate SHA-256 digest: " + expectedCertificateSha256
                    )
                ) {
                    hasExpectedCertificate = true;
                }
            }
        }
        int exitCode = process.waitFor();
        return exitCode == 0 && hasSingleSigner && hasExpectedCertificate;
    }

    private static Console requireConsole() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                "A real local console is required. Run this command in the visible CMD window."
            );
        }
        return console;
    }

    private static char[] readNewPassword(Console console) {
        char[] first = console.readPassword(
            "New signing password (%d-%d characters; 12+ recommended; input is hidden): ",
            MINIMUM_PASSWORD_LENGTH,
            MAXIMUM_PASSWORD_LENGTH
        );
        if (first == null) {
            throw new IllegalStateException("Password input was cancelled.");
        }
        if (first.length < MINIMUM_PASSWORD_LENGTH || first.length > MAXIMUM_PASSWORD_LENGTH) {
            Arrays.fill(first, '\0');
            throw new IllegalArgumentException(
                "Signing password length must be between "
                    + MINIMUM_PASSWORD_LENGTH
                    + " and "
                    + MAXIMUM_PASSWORD_LENGTH
                    + " characters."
            );
        }
        char[] confirmation = console.readPassword("Repeat signing password (input is hidden): ");
        if (confirmation == null) {
            Arrays.fill(first, '\0');
            throw new IllegalStateException("Password confirmation was cancelled.");
        }
        boolean matches = Arrays.equals(first, confirmation);
        Arrays.fill(confirmation, '\0');
        if (!matches) {
            Arrays.fill(first, '\0');
            throw new IllegalArgumentException("The two passwords did not match.");
        }
        return first;
    }

    private static char[] readExistingPassword(Console console) {
        char[] password = console.readPassword("Signing password (input is hidden): ");
        if (password == null || password.length == 0 || password.length > MAXIMUM_PASSWORD_LENGTH) {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            throw new IllegalStateException("Password input was cancelled or invalid.");
        }
        return password;
    }

    private String readPinnedCertificateSha256(boolean required) throws IOException {
        if (!Files.isRegularFile(certificatePinFile)) {
            if (required) {
                throw new IllegalStateException(
                    "The public internal-signing certificate pin is missing. Refusing to build."
                );
            }
            return null;
        }
        String fingerprint = Files.readString(certificatePinFile, StandardCharsets.US_ASCII)
            .trim()
            .toLowerCase(Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                "The public internal-signing certificate pin must contain exactly one SHA-256 fingerprint."
            );
        }
        return fingerprint;
    }

    private String readKeystoreCertificateSha256(Path keyStore, String password) {
        char[] passwordChars = password.toCharArray();
        try (InputStream input = Files.newInputStream(keyStore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, passwordChars);
            if (!store.isKeyEntry(KEY_ALIAS)) {
                return null;
            }
            Certificate certificate = store.getCertificate(KEY_ALIAS);
            if (certificate == null) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return toLowerHex(digest);
        } catch (IOException | GeneralSecurityException exception) {
            return null;
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }

    private int runWithSecret(
        List<String> command,
        String variableName,
        String secret,
        boolean quietOutput
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectOutput(
            quietOutput ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.INHERIT
        );
        Map<String, String> environment = processBuilder.environment();
        clearSigningEnvironment(environment);
        environment.put(variableName, secret);
        Process process;
        try {
            process = processBuilder.start();
        } finally {
            environment.remove(variableName);
        }
        return process.waitFor();
    }

    private ProcessBuilder newReleaseProcess() {
        String commandShell = System.getenv().getOrDefault("ComSpec", "cmd.exe");
        String releaseCommand = "call \"" + releaseScript + "\"";
        ProcessBuilder processBuilder = new ProcessBuilder(
            commandShell,
            "/d",
            "/s",
            "/c",
            releaseCommand
        );
        processBuilder.directory(projectRoot.toFile());
        return processBuilder;
    }

    private static void clearSigningEnvironment(Map<String, String> environment) {
        environment.remove("BILL_VERSION_CODE");
        environment.remove("BILL_VERSION_NAME");
        environment.remove("BILL_RELEASE_STORE_FILE");
        environment.remove("BILL_RELEASE_STORE_PASSWORD");
        environment.remove("BILL_RELEASE_KEY_ALIAS");
        environment.remove("BILL_RELEASE_KEY_PASSWORD");
        environment.remove("BILL_REQUIRE_SIGNED_RELEASE");
        environment.remove(KEY_PASSWORD_ENV);
        environment.remove("BILL_APKSIGNER");
        environment.remove("BILL_ANDROID_SDK");
        environment.remove("BILL_BUILD_TOOLS");
        environment.remove("ANDROID_SDK_ROOT");
        environment.remove("ANDROID_HOME");
    }

    private static void validateVersion(String versionCode, String versionName) {
        if (versionCode == null || !versionCode.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Version code must be a positive integer.");
        }
        try {
            if (Integer.parseInt(versionCode) <= 0) {
                throw new IllegalArgumentException("Version code must be a positive integer.");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Version code is too large.");
        }
        if (
            versionName == null
                || versionName.isBlank()
                || versionName.length() > 100
                || versionName.chars().anyMatch(Character::isISOControl)
        ) {
            throw new IllegalArgumentException(
                "Version name must be non-blank, at most 100 characters, and contain no control characters."
            );
        }
    }

    private static void expectInvalidVersion(String versionCode, String versionName) {
        try {
            validateVersion(versionCode, versionName);
            throw new IllegalStateException("Invalid version input was accepted by the self-test.");
        } catch (IllegalArgumentException expected) {
            // Expected failure.
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        String type = exception.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private static void printUsage() {
        System.err.println(
            "Usage: scripts\\internal-signing.cmd create|build|status|self-test"
        );
    }
}
