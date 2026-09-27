package com.specagent.modelsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ModelCredentialCryptoTest.java
 *
 * 测试目标:模型凭据 AES-GCM 加密组件的单元回归——往返加解密、随机 IV、
 * 迁移前明文兼容读取、错误主密钥显式失败、主密钥文件生成与稳定性、
 * 主密钥轮换(fallback 解密)。
 */
class ModelCredentialCryptoTest {

    private static final String KEY_A = base64Key("key-a-32-bytes-xxxxxxxxxxxxxx!");
    private static final String KEY_B = base64Key("key-b-32-bytes-yyyyyyyyyyyyyy!");

    private static String base64Key(String seed) {
        byte[] raw = new byte[32];
        byte[] seedBytes = seed.getBytes();
        for (int i = 0; i < 32; i++) {
            raw[i] = seedBytes[i % seedBytes.length];
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTripEncryptsAndDecrypts() {
        var crypto = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        String secret = "synthetic-provider-key-123";
        String cipher = crypto.encrypt(secret);
        assertThat(cipher).startsWith("enc:v1:").doesNotContain(secret);
        assertThat(crypto.decrypt(cipher)).isEqualTo(secret);
    }

    @Test
    void blankAndNullPassThrough() {
        var crypto = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.encrypt("")).isEmpty();
        assertThat(crypto.decrypt(null)).isNull();
        assertThat(crypto.decrypt("")).isEmpty();
    }

    @Test
    void randomIvProducesDifferentCiphertexts() {
        var crypto = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        assertThat(crypto.encrypt("same-secret")).isNotEqualTo(crypto.encrypt("same-secret"));
    }

    @Test
    void legacyPlaintextIsReturnedAsIsForMigrationWindow() {
        var crypto = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        assertThat(crypto.decrypt("plaintext-legacy-key")).isEqualTo("plaintext-legacy-key");
        assertThat(crypto.isEncrypted("plaintext-legacy-key")).isFalse();
        assertThat(crypto.isEncrypted(crypto.encrypt("x"))).isTrue();
    }

    @Test
    void wrongMasterKeyFailsLoudlyInsteadOfReturningGarbage() {
        var cryptoA = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        String cipher = cryptoA.encrypt("synthetic-secret");
        var cryptoB = new ModelCredentialCrypto(KEY_B, "", unusedKeyFile());
        // keyId 由密钥派生:错误的密钥无法识别密文的 keyId,显式失败
        assertThatThrownBy(() -> cryptoB.decrypt(cipher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master key");
    }

    @Test
    void unknownKeyIdFailsWithExplicitMessage() {
        var cryptoA = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        String cipher = cryptoA.encrypt("synthetic-secret");
        String tamperedId = "enc:v1:deadbeef:" + cipher.substring("enc:v1:".length() + 9);
        var cryptoB = new ModelCredentialCrypto(KEY_B, "", unusedKeyFile());
        assertThatThrownBy(() -> cryptoB.decrypt(tamperedId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown master key id");
    }

    @Test
    void rotationWithFallbackDecryptsOldCiphertext() {
        var oldCrypto = new ModelCredentialCrypto(KEY_A, "", unusedKeyFile());
        String oldCipher = oldCrypto.encrypt("synthetic-secret");
        // 新主密钥 KEY_B + fallback KEY_A:解密旧密文成功;新加密用新 keyId
        var rotated = new ModelCredentialCrypto(KEY_B, KEY_A, unusedKeyFile());
        assertThat(rotated.decrypt(oldCipher)).isEqualTo("synthetic-secret");
        String newCipher = rotated.encrypt("synthetic-secret");
        assertThat(rotated.keyIdOfCiphertext(newCipher)).isEqualTo(rotated.primaryKeyId());
        assertThat(rotated.keyIdOfCiphertext(oldCipher)).isNotEqualTo(rotated.primaryKeyId());
    }

    @Test
    void keyFileIsGeneratedOnceAndReusedAcrossRestarts(@TempDir Path tempDir) {
        Path keyFile = tempDir.resolve("data/secret-master.key");
        var first = new ModelCredentialCrypto("", "", keyFile.toString());
        String cipher = first.encrypt("synthetic-secret");
        // 模拟重启:同一个文件再次加载,历史密文可解
        var second = new ModelCredentialCrypto("", "", keyFile.toString());
        assertThat(second.decrypt(cipher)).isEqualTo("synthetic-secret");
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void corruptKeyFileFailsClosed(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("secret-master.key");
        Files.writeString(keyFile, "not-valid-base64!!!");
        assertThatThrownBy(() -> new ModelCredentialCrypto("", "", keyFile.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void invalidPropertyKeyFailsClosed() {
        assertThatThrownBy(() -> new ModelCredentialCrypto("too-short", "", unusedKeyFile()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String unusedKeyFile() {
        // 属性路径已提供主密钥,文件路径不会被使用
        return "unused-key-file-not-created.key";
    }
}
