package dev.nibin.buzzer.identity;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * A throwaway RSA key pair for tests, written as PEM files to a temp directory once per JVM.
 * Importing this configuration points identity.jwt.* at those files, so the real file-loading code
 * runs and no private key is ever committed. (The application itself never generates keys.)
 * The static helpers are also used directly by unit tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtKeys {

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final Path DIRECTORY = createTempDirectory();
    private static final Path PRIVATE_KEY = writePrivateKey(KEY_PAIR, DIRECTORY.resolve("jwt-private.pem"));
    private static final Path PUBLIC_KEY = writePublicKey(KEY_PAIR, DIRECTORY.resolve("jwt-public.pem"));

    /** Spring applies registrar beans before other beans are created, so JwtConfig sees these properties. */
    @Bean
    DynamicPropertyRegistrar jwtKeyLocations() {
        return TestJwtKeys::register;
    }

    /** toUri() gives file:///C:/... on Windows, file:///tmp/... on Linux. */
    private static void register(DynamicPropertyRegistry registry) {
        registry.add("identity.jwt.private-key-location", () -> PRIVATE_KEY.toUri().toString());
        registry.add("identity.jwt.public-key-location", () -> PUBLIC_KEY.toUri().toString());
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCS#8, like `openssl genpkey` in tasks.ps1. */
    public static Path writePrivateKey(KeyPair keyPair, Path file) {
        return writePem(file, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    /** X.509 SubjectPublicKeyInfo, like `openssl pkey -pubout` in tasks.ps1. */
    public static Path writePublicKey(KeyPair keyPair, Path file) {
        return writePem(file, "PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    private static Path writePem(Path file, String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
        try {
            Files.writeString(file, pem, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        file.toFile().deleteOnExit();
        return file;
    }

    private static Path createTempDirectory() {
        try {
            Path directory = Files.createTempDirectory("buzzer-jwt-test");
            directory.toFile().deleteOnExit(); // registered first, so deleted after the files inside it
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
