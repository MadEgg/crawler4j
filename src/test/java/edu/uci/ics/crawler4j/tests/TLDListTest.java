package edu.uci.ics.crawler4j.tests;

import static org.junit.Assert.assertEquals;

import java.net.URISyntaxException;

import org.junit.Test;

import edu.uci.ics.crawler4j.url.URLCanonicalizer;
import edu.uci.ics.crawler4j.url.WebURL;

public class TLDListTest {

  private WebURL webUrl;

  private void setUrl(String url) throws URISyntaxException {
    webUrl = new WebURL(URLCanonicalizer.getCanonicalURL(url));
  }

  @Test
  public void testTLD() throws URISyntaxException {

    setUrl("http://example.com");
    assertEquals("example.com", webUrl.getDomain());
    assertEquals("", webUrl.getSubDomain());

    setUrl("http://test.example.com");
    assertEquals("example.com", webUrl.getDomain());
    assertEquals("test", webUrl.getSubDomain());

    setUrl("http://test2.test.example.com");
    assertEquals("example.com", webUrl.getDomain());
    assertEquals("test2.test", webUrl.getSubDomain());

    setUrl("http://test3.test2.test.example.com");
    assertEquals("example.com", webUrl.getDomain());
    assertEquals("test3.test2.test", webUrl.getSubDomain());

    setUrl("http://www.example.ac.jp");
    assertEquals("example.ac.jp", webUrl.getDomain());
    assertEquals("www", webUrl.getSubDomain());

    setUrl("http://example.ac.jp");
    assertEquals("example.ac.jp", webUrl.getDomain());
    assertEquals("", webUrl.getSubDomain());
  }
}