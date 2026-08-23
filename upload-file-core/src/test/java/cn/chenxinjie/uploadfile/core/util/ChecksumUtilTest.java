package cn.chenxinjie.uploadfile.core.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class ChecksumUtilTest {

    @Test
    public void md5OfBytes() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                ChecksumUtil.md5("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha1OfBytes() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                ChecksumUtil.sha1("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha256OfBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ChecksumUtil.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void checksumIsLowerCaseAndStable() {
        String s = "hello world";
        String a = ChecksumUtil.md5(s.getBytes(StandardCharsets.UTF_8));
        String b = ChecksumUtil.md5(s.getBytes(StandardCharsets.UTF_8));
        assertEquals(a, b);
        assertEquals(a.toLowerCase(), a);
    }
}
