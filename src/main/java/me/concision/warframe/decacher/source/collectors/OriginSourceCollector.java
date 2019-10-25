package me.concision.warframe.decacher.source.collectors;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Delegate;
import lombok.extern.log4j.Log4j2;
import me.concision.warframe.decacher.CommandArguments;
import me.concision.warframe.decacher.api.PackageDecompressionInputStream;
import me.concision.warframe.decacher.api.TocStreamReader;
import me.concision.warframe.decacher.api.TocStreamReader.PackageEntry;
import me.concision.warframe.decacher.source.SourceCollector;
import me.concision.warframe.decacher.source.SourceType;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.utils.BoundedInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;

/**
 * See {@link SourceType#ORIGIN}
 *
 * @author Concision
 * @date 10/21/2019
 */
@Log4j2
public class OriginSourceCollector implements SourceCollector {
    @Override
    public InputStream generate(@NonNull CommandArguments args) {
        // obtain listing of CDN depot files
        @RequiredArgsConstructor
        @ToString
        class DepotFile {
            final String path;
            final String name;
            final long size;
        }
        List<DepotFile> files = new LinkedList<>();

        log.info("Fetching index.txt.lzma manifest");
        try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                .addInterceptorLast(new HeaderFormatter())
                .build()) {
            // form index.txt.lzma request
            HttpGet request = new HttpGet("http://origin.warframe.com/index.txt.lzma");
            request.setProtocolVersion(HttpVersion.HTTP_1_1);

            // execute request
            CloseableHttpResponse response = httpClient.execute(request);

            // parse response
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new LZMACompressorInputStream(response.getEntity().getContent())))) {
                // path parsing pattern
                Pattern pathPattern = Pattern.compile("^(?:/[^/]+)*/(.+)\\.[0-9A-F]{32}\\.lzma$");
                // parse entries in manifest
                for (String entry; (entry = reader.readLine()) != null; ) {
                    int commaIndex = entry.lastIndexOf(',');
                    if (commaIndex == -1) {
                        continue;
                    }
                    // parse parameter
                    String path = entry.substring(0, commaIndex);
                    long fileSize = Long.parseLong(entry.substring(commaIndex + 1));
                    String name;

                    Matcher matcher = pathPattern.matcher(path);
                    if (matcher.find()) {
                        name = matcher.group(1);
                    } else {
                        name = path.substring(path.lastIndexOf('.') + 1);
                    }

                    files.add(new DepotFile(path, name, fileSize));
                }
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("failed to fetch index.txt.lzma manifest", throwable);
        }

        // find Packages.bin TOC entry
        log.info("Fetching H.Misc.toc");
        PackageEntry packageEntry;
        try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                .addInterceptorLast(new HeaderFormatter())
                .build()) {
            // build TOC url
            String tocUrl = files.stream()
                    .filter(file -> file.name.equals("H.Misc.toc"))
                    .findFirst()
                    .map(entry -> "http://content.warframe.com" + entry.path)
                    .orElseThrow(() -> new RuntimeException("failed to find H.Misc.toc in manifest"));

            // form request
            HttpGet request = new HttpGet(tocUrl);
            request.setProtocolVersion(HttpVersion.HTTP_1_1);

            // execute request
            CloseableHttpResponse response = httpClient.execute(request);

            // parse response in real-time
            try (InputStream stream = new LZMACompressorInputStream(response.getEntity().getContent())) {

                Optional<PackageEntry> xd = new TocStreamReader(new BufferedInputStream(stream)).findEntry("/Packages.bin");

                if (!xd.isPresent()) {
                    throw new RuntimeException("failed to find Packages.bin");
                }

                packageEntry = xd.get();
            }
            response.close();
        } catch (EOFException ignored) {
            throw new RuntimeException("reached EOF before finding Packages.bin");
        } catch (Throwable throwable) {
            throw new RuntimeException("failed to parse H.Misc.toc or find Packages.bin entry", throwable);
        }

        log.info("Retrieving H.Misc.cache...");
        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .addInterceptorLast(new HeaderFormatter())
                .build();
        try {
            // build TOC url
            String cacheUrl = files.stream()
                    .filter(file -> file.name.equals("H.Misc.cache"))
                    .findFirst()
                    .map(entry -> "http://content.warframe.com" + entry.path)
                    .orElseThrow(() -> new RuntimeException("failed to find H.Misc.cache in manifest"));
            log.info(cacheUrl);

            // download APK file
            HttpGet request = new HttpGet(cacheUrl);
            request.setProtocolVersion(HttpVersion.HTTP_1_1);
            CloseableHttpResponse response = httpClient.execute(request);
            HttpEntity entity = response.getEntity();

            log.info(response.getStatusLine());

            InputStream input = new BufferedInputStream(new LZMACompressorInputStream(new BufferedInputStream(entity.getContent())));
            // skip bytes
            log.info("Skipping useless files...");
            IOUtils.skip(input, packageEntry.offset());
            log.info("Reading Packages.bin");

            // Warframe Packages.bin decompressor
            return new DependentInputStream(new PackageDecompressionInputStream(new BoundedInputStream(
                    input,
                    ((int) Math.round(Math.ceil(packageEntry.compressedSize() / (double) Character.MAX_VALUE)) + 1) * Character.MAX_VALUE
            )), httpClient);
        } catch (Throwable throwable) {
            IOUtils.closeQuietly(httpClient);
            throw new RuntimeException("failed to fetch H.Misc.cache", throwable);
        }
    }

    /**
     * Formats header to not provide much client information
     */
    private static class HeaderFormatter implements HttpRequestInterceptor {
        @Override
        public void process(HttpRequest request, HttpContext httpContext) {
            // preserve specific host and header
            Header hostHeader = request.getFirstHeader("Host");

            // strip all headers
            for (HeaderIterator iterator = request.headerIterator(); iterator.hasNext(); ) {
                iterator.nextHeader();
                iterator.remove();
            }

            // rebuild headers
            if (hostHeader != null) {
                request.setHeader("Host", hostHeader.getValue());
            }
            request.setHeader("Connection", "Keep-Alive");
            request.setHeader("Pragma", "no-cache");
        }
    }

    /**
     * Closes another {@link Closeable instance} upon stream {@link InputStream#close()}
     */
    @RequiredArgsConstructor
    private static class DependentInputStream extends InputStream implements Closeable {
        @Delegate(excludes = Closeable.class)
        private final InputStream stream;

        private final Closeable closeable;

        @Override
        public void close() throws IOException {
            IOUtils.closeQuietly(closeable);
            stream.close();
        }
    }
}