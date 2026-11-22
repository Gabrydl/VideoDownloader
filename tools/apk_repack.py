#!/usr/bin/env python3
"""Rebuild an APK with new assets, zip-align it and sign it (APK Signature Scheme v1 + v2).

usage: apk_repack.py ORIGINAL.apk OUT.apk KEY_DIR [--assets DIR:PREFIX ...] [--version-code N] [--version-name X.Y.Z]

KEY_DIR holds key.pem + cert.der; they are created the first time. Keep them: Android only
installs an update over an app signed with the same key.
"""
import argparse, base64, datetime, hashlib, io, os, struct, sys, zipfile
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.serialization import pkcs7
from cryptography.x509.oid import NameOID

STORE_EXT = (".png", ".jpg", ".jpeg", ".gz", ".woff2", ".webp", ".mp3", ".ogg", ".zip")

# ---------------------------------------------------------------- key
def load_key(d, cn):
    os.makedirs(d, exist_ok=True)
    kp, cp = os.path.join(d, "key.pem"), os.path.join(d, "cert.der")
    if not os.path.exists(kp):
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn), x509.NameAttribute(NameOID.ORGANIZATION_NAME, "gabrydl99")])
        now = datetime.datetime(2026, 11, 1, tzinfo=datetime.timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now)
                .not_valid_after(now + datetime.timedelta(days=365 * 40)).sign(key, hashes.SHA256()))
        open(kp, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        open(cp, "wb").write(cert.public_bytes(serialization.Encoding.DER))
        print("new signing key created in", d)
    key = serialization.load_pem_private_key(open(kp, "rb").read(), None)
    cert = x509.load_der_x509_certificate(open(cp, "rb").read())
    return key, cert

# ---------------------------------------------------------------- manifest patch (same-length edits in the binary XML)
def patch_manifest(axml, version_code=None, version_name=None, old_name=None):
    b = bytearray(axml)
    if version_name and old_name:
        assert len(version_name) == len(old_name), "versionName must keep the same length"
        for enc in ("utf-16-le", "utf-8"):
            o, n = old_name.encode(enc), version_name.encode(enc)
            i = b.find(o)
            if i >= 0:
                b[i:i + len(o)] = n
                break
        else:
            raise SystemExit("versionName not found in the manifest")
    if version_code is not None:
        # walk the binary XML: find the resource map, then the <manifest> start element and its versionCode attribute
        assert struct.unpack_from("<HH", b, 0) == (0x0003, 8)
        off, res_ids, done = 8, [], False
        while off < len(b) and not done:
            ctype, hsize, csize = struct.unpack_from("<HHI", b, off)
            if ctype == 0x0180:
                res_ids = [struct.unpack_from("<I", b, off + 8 + 4 * k)[0] for k in range((csize - 8) // 4)]
            elif ctype == 0x0102:
                ext = off + hsize
                a_start, a_size, a_count = struct.unpack_from("<HHH", b, ext + 8)
                for k in range(a_count):
                    at = ext + a_start + k * a_size
                    name = struct.unpack_from("<I", b, at + 4)[0]
                    if name < len(res_ids) and res_ids[name] == 0x0101021B:
                        assert b[at + 15] in (0x10, 0x11), "versionCode is not an integer"
                        struct.pack_into("<I", b, at + 16, version_code)
                        done = True
                break_after = True
                if done:
                    break
            off += csize
        assert done, "versionCode not found"
    return bytes(b)

# ---------------------------------------------------------------- zip with alignment
def build_zip(entries):
    """entries: list of (name, bytes, store:bool). STORED entries are aligned to 4 bytes (4096 for .so)."""
    out = io.BytesIO()
    z = zipfile.ZipFile(out, "w")
    for name, data, store in entries:
        zi = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 2))
        zi.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        if store:
            align = 4096 if name.endswith(".so") else 4
            hdr = out.tell() + 30 + len(name.encode())
            pad = (-(hdr + 6)) % align
            zi.extra = struct.pack("<HHH", 0xD935, 2 + pad, align) + b"\0" * pad
        z.writestr(zi, data, compresslevel=9 if not store else None)
    z.close()
    return out.getvalue()

# ---------------------------------------------------------------- v1 (JAR) signature
def v1_sign(entries, key, cert, signer="CERT"):
    def b64(d): return base64.b64encode(hashlib.sha256(d).digest()).decode()
    mf = ["Manifest-Version: 1.0", "Created-By: PdfEditor build", ""]
    sections = []
    for name, data, _ in entries:
        sec = f"Name: {name}\r\nSHA-256-Digest: {b64(data)}\r\n\r\n"
        sections.append(sec)
    mf_bytes = ("\r\n".join(mf) + "\r\n").encode() + "".join(sections).encode()
    head = "Signature-Version: 1.0\r\nCreated-By: PdfEditor build\r\nX-Android-APK-Signed: 2\r\n"
    sf = head + f"SHA-256-Digest-Manifest: {b64(mf_bytes)}\r\n\r\n"
    for sec in sections:
        name = sec.split("\r\n")[0][6:]
        sf += f"Name: {name}\r\nSHA-256-Digest: {b64(sec.encode())}\r\n\r\n"
    sf = sf.encode()
    sig = (pkcs7.PKCS7SignatureBuilder().set_data(sf).add_signer(cert, key, hashes.SHA256())
           .sign(serialization.Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature, pkcs7.PKCS7Options.NoAttributes]))
    return [("META-INF/MANIFEST.MF", mf_bytes, False), (f"META-INF/{signer}.SF", sf, False), (f"META-INF/{signer}.RSA", sig, False)]

# ---------------------------------------------------------------- v2 signature
def lp(b): return struct.pack("<I", len(b)) + b

def v2_sign(apk, key, cert):
    eocd = apk.rfind(b"PK\x05\x06")
    assert eocd > 0 and len(apk) - eocd == 22, "zip comment not supported"
    cd_size, cd_off = struct.unpack_from("<II", apk, eocd + 12)
    sections = [apk[:cd_off], apk[cd_off:eocd], apk[eocd:]]
    chunks = []
    for s in sections:
        for i in range(0, len(s), 1 << 20):
            c = s[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(c)) + c).digest())
    top = hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()
    ALG = 0x0103  # RSASSA-PKCS1-v1_5 with SHA2-256
    digests = lp(lp(struct.pack("<I", ALG) + lp(top)))
    certs = lp(lp(cert.public_bytes(serialization.Encoding.DER)))
    signed_data = digests + certs + lp(b"")
    sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed_data) + lp(lp(struct.pack("<I", ALG) + lp(sig))) + lp(pub)
    value = lp(lp(signer))
    pair = struct.pack("<I", 0x7109871A) + value
    pairs = struct.pack("<Q", len(pair)) + pair
    size = len(pairs) + 8 + 16
    block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"
    new_eocd = bytearray(sections[2])
    struct.pack_into("<I", new_eocd, 16, cd_off + len(block))
    return sections[0] + block + sections[1] + bytes(new_eocd)

# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("original"); ap.add_argument("out"); ap.add_argument("keydir")
    ap.add_argument("--assets", action="append", default=[], help="DIR:PREFIX, replaces everything under PREFIX")
    ap.add_argument("--drop", action="append", default=[], help="remove entries starting with this")
    ap.add_argument("--version-code", type=int); ap.add_argument("--version-name"); ap.add_argument("--old-version-name")
    ap.add_argument("--cn", default="gabrydl99")
    a = ap.parse_args()

    src = zipfile.ZipFile(a.original)
    prefixes = [x.split(":", 1)[1] for x in a.assets]
    entries = []
    for zi in src.infolist():
        n = zi.filename
        if n.startswith("META-INF/") and (n.endswith((".SF", ".RSA", ".DSA", ".EC")) or n == "META-INF/MANIFEST.MF"):
            continue
        if any(n.startswith(p) for p in prefixes + a.drop):
            continue
        data = src.read(n)
        if n == "AndroidManifest.xml" and (a.version_code or a.version_name):
            data = patch_manifest(data, a.version_code, a.version_name, a.old_version_name)
        entries.append((n, data, zi.compress_type == zipfile.ZIP_STORED))
    for spec in a.assets:
        d, prefix = spec.split(":", 1)
        for root, _dirs, files in sorted(os.walk(d)):
            for f in sorted(files):
                p = os.path.join(root, f)
                rel = os.path.relpath(p, d).replace(os.sep, "/")
                entries.append((prefix + rel, open(p, "rb").read(), f.lower().endswith(STORE_EXT) or f.endswith(".wasm")))
    key, cert = load_key(a.keydir, a.cn)
    # resources.arsc must be stored and aligned (Android 11+); put it early like the build tools do
    entries.sort(key=lambda e: (e[0] != "AndroidManifest.xml", not e[0].startswith("res/") and e[0] != "resources.arsc", 0))
    entries = [(n, d, True if n == "resources.arsc" else s) for n, d, s in entries]
    entries += v1_sign(entries, key, cert)
    apk = v2_sign(build_zip(entries), key, cert)
    open(a.out, "wb").write(apk)
    fp = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    print(f"wrote {a.out} ({len(apk) / 1e6:.1f} MB), certificate SHA-256 {fp}")

if __name__ == "__main__":
    main()
