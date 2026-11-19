# Local HTTPS test fixture only

`localhost.p12` is a self-signed RSA test certificate and private key for localhost/127.0.0.1, generated specifically for this repository's local HTTPS protocol tests. It is not an API credential, production identity, or trusted public certificate. The intentionally public test password is `cutedog-test-only`.

The fixture is only in `src/test/resources` and is not packaged into the APK. Tests use a private SSLContext that trusts this certificate, retaining the default hostname verifier. Production uses Android platform trust unchanged. Tests also verify that default trust rejects the fixture certificate.

Regenerate after expiry with JDK keytool:

```
keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 3650 -dname 'CN=localhost,OU=CuteDog Test Fixture,O=Local Tests,C=CN' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 -keystore localhost.p12 -storepass cutedog-test-only -keypass cutedog-test-only -noprompt
```

Server responses explicitly named `fixture-response` are test data, not user-facing AI replies or proof of model quality.
