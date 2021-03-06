/*
 * (c) 2016 Novetta
 *
 * Created by mike
 */
package com.github.mike10004.seleniumtrafficeg;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.gson.JsonParser;
import net.lightbody.bmp.BrowserMobProxy;
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.mitm.RootCertificateGenerator;
import net.lightbody.bmp.mitm.manager.ImpersonatingMitmManager;
import org.apache.commons.io.FileUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;

public class GenerateNewCertificate {

    // https://github.com/lightbody/browsermob-proxy/blob/master/mitm/src/test/java/net/lightbody/bmp/mitm/example/SaveGeneratedCAExample.java
    public static void main(String[] args) throws Exception {
        // create a dynamic CA root certificate generator using default settings (2048-bit RSA keys)
        RootCertificateGenerator rootCertificateGenerator = RootCertificateGenerator.builder().build();
        File outputDir = FileUtils.getTempDirectory();
        File keystoreFile = File.createTempFile("dynamically-generated-certificate", ".keystore", outputDir);
        /*
         * See https://stackoverflow.com/questions/652916/converting-a-java-keystore-into-pem-format
         * for instructions on how to convert the .keystore file into a .pem file that can be installed
         * into browsers like Firefox.
         *
         * In short, if $KEYSTORE_FILE is the file generated by this program, execute:
         *
         *     $ keytool -importkeystore -srckeystore $KEYSTORE_FILE -destkeystore temp.p12 -srcstoretype jks  -deststoretype pkcs12
         *     $ openssl pkcs12 -in temp.p12 -out exported-keystore.pem
         *
         * The contents of `exported-keystore.pem` will be in PEM format.
         */
        String keystorePassword = TrafficEater.CustomCertificate.KEYSTORE_PASSWORD;
        rootCertificateGenerator.saveRootCertificateAndKey(TrafficEater.CustomCertificate.KEYSTORE_TYPE,
                keystoreFile, TrafficEater.CustomCertificate.KEYSTORE_PRIVATE_KEY_ALIAS, keystorePassword);
        System.out.format("certificate written to %s%n", keystoreFile);
        useCertificate(rootCertificateGenerator, keystoreFile, keystorePassword);
    }

    private static void useCertificate(RootCertificateGenerator rootCertificateGenerator, File keystoreFile, String keystorePassword) throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        ImpersonatingMitmManager mitmManager = ImpersonatingMitmManager.builder()
                .rootCertificateSource(rootCertificateGenerator)
                .build();
        SSLContext customSslContext = SSLContexts.custom()
                .loadTrustMaterial(keystoreFile, keystorePassword.toCharArray(), new TrustSelfSignedStrategy())
                .build();
        BrowserMobProxy proxy = new BrowserMobProxyServer();
        proxy.setMitmManager(mitmManager);
        proxy.start();
        try (CloseableHttpClient client = HttpClients.custom()
                .setSSLContext(customSslContext)
                .setProxy(new HttpHost("localhost", proxy.getPort()))
                .build()) {
            System.out.println("sending request...");
            String responseText;
            try (CloseableHttpResponse response = client.execute(new HttpGet(URI.create("https://httpbin.org/get")))) {
                responseText = EntityUtils.toString(response.getEntity());
                System.out.format("response headers: %s%n", Iterables.transform(Arrays.asList(response.getAllHeaders()), new Function<Header, Object>() {
                    @Override
                    public String apply(Header input) {
                        return String.format("%s=%s", input.getName(), input.getValue());
                    }
                }));
            }
            System.out.println(responseText);
            String viaHeader = new JsonParser().parse(responseText).getAsJsonObject().get("headers").getAsJsonObject().get("Via").getAsString();
            checkState("1.1 browsermobproxy".equals(viaHeader), "'Via' header should specify that browsermobproxy was used, but its value is %s", viaHeader);
        } finally {
            proxy.stop();
        }
    }}
