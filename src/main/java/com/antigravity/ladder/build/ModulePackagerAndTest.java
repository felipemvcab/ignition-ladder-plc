package com.antigravity.ladder.build;

import com.antigravity.ladder.engine.CyclicScanEngine;
import com.antigravity.ladder.engine.TagImageManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Verifies the LadderPLC Scan Engine, Subroutines (JSR/RET), Seal-In circuits,
 * Dirty-Write Optimization, and packages a signed Ignition 8.3 .modl file.
 */
public class ModulePackagerAndTest {

    public static void main(String[] args) throws Exception {
        boolean skipTests = args.length >= 4 && "skipTests".equalsIgnoreCase(args[3]);
        if (!skipTests) {
            runTestsAndBenchmarks();
        }

        if (args.length >= 2) {
            File jarFile = new File(args[0]);
            File outModl = new File(args[1]);
            String reqIgnVer = args.length >= 3 && !args[2].isBlank() ? args[2].trim() : "8.3.0";
            packageSignedModl(jarFile, outModl, reqIgnVer);
        }
    }

    private static void runTestsAndBenchmarks() throws Exception {
        System.out.println("==================================================================");
        System.out.println(" 1. RUNNING LADDER LOGIC SCAN ENGINE VERIFICATION & BENCHMARK");
        System.out.println("==================================================================");

        File tempStorage = new File("/tmp/ladder-plc-test-project.json");
        tempStorage.delete();

        TagImageManager tagImage = new TagImageManager(null, null);
        CyclicScanEngine engine = new CyclicScanEngine(tagImage, tempStorage);
        engine.loadDefaultIndustrialProject();

        // Test 1: Initial Scan & Subroutine Dispatch (MainRoutine -> JSR Sub_PumpControl, Sub_TankSimulation, Sub_AlarmManager)
        engine.executeSingleScanSafely();
        assertTrue("MainRoutine scanned", engine.getFullStudioSnapshot().toString().contains("MainRoutine"));

        // Test 2: Momentary Start_PB energizes Pump1_Run and seals in after Start_PB is released!
        tagImage.writeFromExternalUI("[LadderPLC]ControllerTags/Start_PB", true);
        engine.executeSingleScanSafely();
        boolean pumpRunning = tagImage.readBoolean("[LadderPLC]ControllerTags/Pump1_Run");
        assertTrue("Pump1_Run energized when Start_PB pressed", pumpRunning);

        // Release Start_PB -> Seal-in branch (NXB XIC Pump1_Run) must keep Pump1_Run TRUE!
        tagImage.writeFromExternalUI("[LadderPLC]ControllerTags/Start_PB", false);
        engine.executeSingleScanSafely();
        boolean stillRunning = tagImage.readBoolean("[LadderPLC]ControllerTags/Pump1_Run");
        assertTrue("Pump1_Run stays sealed-in after Start_PB released", stillRunning);

        // Test 3: Tank fills via Sub_TankSimulation (called via JSR from MainRoutine) until HighLevel_Alarm trips at 85%
        double initialLevel = tagImage.readDouble("[LadderPLC]ControllerTags/Tank_Level");
        for (int i = 0; i < 220; i++) {
            engine.executeSingleScanSafely();
        }
        boolean hiAlarmLatched = tagImage.readBoolean("[LadderPLC]ControllerTags/HighLevel_Alarm");
        boolean pumpTrippedOff = !tagImage.readBoolean("[LadderPLC]ControllerTags/Pump1_Run");
        boolean drainOpened = tagImage.readBoolean("[LadderPLC]ControllerTags/Drain_Valve");
        assertTrue("HighLevel_Alarm latched (OTL) at >= 85% tank level (initial was " + initialLevel + "%)", hiAlarmLatched);
        assertTrue("Pump1_Run automatically interlocked OFF by HighLevel_Alarm", pumpTrippedOff);
        assertTrue("Drain_Valve opened automatically on HighLevel_Alarm", drainOpened);

        // Test 4: Conditional JSR test - Turn off Auto_Mode and verify Sub_TankSimulation stops changing Tank_Level
        tagImage.writeFromExternalUI("[LadderPLC]ControllerTags/Auto_Mode", false);
        double levelBeforePausedSub = tagImage.readDouble("[LadderPLC]ControllerTags/Tank_Level");
        engine.executeSingleScanSafely();
        double levelAfterPausedSub = tagImage.readDouble("[LadderPLC]ControllerTags/Tank_Level");
        assertTrue("Conditional JSR (Auto_Mode=FALSE) skipped Sub_TankSimulation", Math.abs(levelBeforePausedSub - levelAfterPausedSub) < 1e-6);

        // Test 5: Tag Forcing test
        tagImage.setForce("[LadderPLC]ControllerTags/Pump1_Run", true);
        engine.executeSingleScanSafely();
        assertTrue("Forced Pump1_Run remains TRUE despite interlocks", tagImage.readBoolean("[LadderPLC]ControllerTags/Pump1_Run"));
        tagImage.removeForce("[LadderPLC]ControllerTags/Pump1_Run");

        // Test 6: Randomized All-Instructions Validation Suite
        long seed = System.currentTimeMillis();
        System.out.println(" -> Running Randomized All-Instructions Validation Suite (seed=" + seed + ")...");
        InstructionValidationSuite valSuite = new InstructionValidationSuite(seed);
        assertTrue("All randomized instruction validations passed", valSuite.runAll());

        // Test 7: High-Speed 1,000-Scan Performance Benchmark
        long benchStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            engine.executeSingleScanSafely();
        }
        double totalBenchMs = (System.nanoTime() - benchStart) / 1_000_000.0;
        double avgPerScanUs = (totalBenchMs * 1000.0) / 1000.0;
        System.out.printf(" -> 1,000 Full Multi-Subroutine Scans completed in %.2f ms (%.2f µs / scan)%n", totalBenchMs, avgPerScanUs);
    }

    private static void packageSignedModl(File jarFile, File outModl, String requiredIgnitionVersion) throws Exception {
        System.out.println("==================================================================");
        System.out.println(" 2. PACKAGING & SIGNING IGNITION " + requiredIgnitionVersion + " MODULE (.modl)");
        System.out.println("==================================================================");

        Security.addProvider(new BouncyCastleProvider());

        String moduleXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<modules>\n"
                + "\t<module>\n"
                + "\t\t<name>Ladder Logic Studio</name>\n"
                + "\t\t<id>com.antigravity.ladderplc</id>\n"
                + "\t\t<version>1.0.0</version>\n"
                + "\t\t<description>Studio 5000 style Ladder Logic SoftPLC Engine &amp; Live Web IDE with Gateway Tag binding, Subroutines (JSR/SBR/RET), and Dirty-Write Batching.</description>\n"
                + "\t\t<vendorName>Felipe Integrations</vendorName>\n"
                + "\t\t<requiredIgnitionVersion>" + requiredIgnitionVersion + "</requiredIgnitionVersion>\n"
                + "\t\t<requiredFrameworkVersion>8</requiredFrameworkVersion>\n"
                + "\t\t<freeModule>true</freeModule>\n"
                + "\t\t<hook scope=\"G\">com.antigravity.ladder.gateway.LadderGatewayHook</hook>\n"
                + "\t\t<jar scope=\"G\">" + jarFile.getName() + "</jar>\n"
                + "\t</module>\n"
                + "</modules>\n";

        byte[] moduleXmlBytes = moduleXml.getBytes(StandardCharsets.UTF_8);
        byte[] jarBytes = Files.readAllBytes(jarFile.toPath());

        // Persist and reuse RSA-2048 Code-Signing Certificate for "Felipe Integrations" so certFingerprint is permanent
        File keyDir = jarFile.getParentFile().getParentFile().getParentFile();
        File certFile = new File(keyDir, "felipe-integrations-cert.der");
        File privKeyFile = new File(keyDir, "felipe-integrations-key.der");

        java.security.PrivateKey privateKey;
        byte[] certDerBytes;

        if (certFile.exists() && privKeyFile.exists()) {
            certDerBytes = Files.readAllBytes(certFile.toPath());
            byte[] keyBytes = Files.readAllBytes(privKeyFile.toPath());
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            KeyPair keyPair = kpg.generateKeyPair();
            privateKey = keyPair.getPrivate();

            X500Name issuer = new X500Name("CN=Felipe Integrations, O=Felipe Integrations, C=US");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date(System.currentTimeMillis() - 86400_000L * 7);
            Date notAfter = new Date(System.currentTimeMillis() + 86400_000L * 3650);

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
            );
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            certBuilder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_codeSigning));

            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey);
            X509CertificateHolder certHolder = certBuilder.build(contentSigner);
            X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);
            certDerBytes = cert.getEncoded();

            Files.write(certFile.toPath(), certDerBytes);
            Files.write(privKeyFile.toPath(), privateKey.getEncoded());
        }

        // Sign module.xml and the gateway jar with SHA256withRSA
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("module.xml", moduleXmlBytes);
        entries.put(jarFile.getName(), jarBytes);

        Properties sigProps = new Properties();
        Signature rsaSig = Signature.getInstance("SHA256withRSA");
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            rsaSig.initSign(privateKey);
            rsaSig.update(entry.getValue());
            String b64 = Base64.getEncoder().encodeToString(rsaSig.sign());
            sigProps.setProperty("/" + entry.getKey(), b64);
        }

        ByteArrayOutputStream sigBos = new ByteArrayOutputStream();
        sigProps.store(sigBos, null);
        byte[] sigPropsBytes = sigBos.toByteArray();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outModl))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("certificates.p7b"));
            zos.write(certDerBytes);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("signatures.properties"));
            zos.write(sigPropsBytes);
            zos.closeEntry();
        }

        System.out.println(" -> Packaged Signed Module: " + outModl.getAbsolutePath() + " (" + outModl.length() + " bytes)");
        String fp = com.inductiveautomation.ignition.gateway.modules.ModuleUtil.readCertificateFingerprint(outModl);
        var info = com.inductiveautomation.ignition.gateway.modules.ModuleUtil.readModuleInfo(outModl);
        System.out.println(" -> Verified by Ignition ModuleUtil: id=" + info.getId() + ", name=" + info.getName() + ", certFingerprint=" + fp);
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("TEST FAILED: " + label);
        }
        System.out.println(" [PASS] " + label);
    }
}
