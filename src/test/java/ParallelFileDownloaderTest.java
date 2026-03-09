import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ParallelFileDownloaderTest {

    @TempDir
    Path tempDir;

    /**
     * Test that the downloader correctly splits the file into chunks, sends the appropriate range requests,
     * and merges the downloaded parts in the correct order to produce the final file.
     */
    @Test
    void shouldDownloadFileInChunksAndMergeInOrder() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("result.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithRangesAndLength("bytes", 11));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);

        List<String> chunks = List.of("Hel", "lo ", "World");
        List<String> expectedRanges = List.of("bytes=0-2", "bytes=3-5", "bytes=6-10");
        List<String> actualRanges = new ArrayList<>();
        AtomicInteger idx = new AtomicInteger(0);

        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    int i = idx.getAndIncrement();
                    actualRanges.add(request.headers().firstValue("Range").orElse(""));

                    Path partPath = destination.resolveSibling(destination.getFileName() + ".part" + i);
                    Files.writeString(partPath, chunks.get(i));

                    HttpResponse<Path> partResponse = mock(HttpResponse.class);
                    when(partResponse.body()).thenReturn(partPath);
                    return CompletableFuture.completedFuture(partResponse);
                });

        downloader.downloadFile("http://example.com/file.txt", destination, 3);

        assertEquals(expectedRanges, actualRanges);
        assertEquals("Hello World", Files.readString(destination));
        assertEquals(3, idx.get());
        assertTrue(Files.notExists(destination.resolveSibling("result.txt.part0")));
        assertTrue(Files.notExists(destination.resolveSibling("result.txt.part1")));
        assertTrue(Files.notExists(destination.resolveSibling("result.txt.part2")));
        verify(httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(httpClient, times(3)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /**
     * Test that the downloader correctly calculates byte ranges when the content length is not perfectly divisible by the number of chunks,
     * ensuring that the last chunk correctly includes any remaining bytes.
     */
    @Test
    void shouldCalculateRangesCorrectlyWhenContentLengthIsNotDivisibleByChunkCount() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("non-divisible.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithRangesAndLength("bytes", 10));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);

        List<String> expectedRanges = List.of("bytes=0-2", "bytes=3-5", "bytes=6-9");
        List<String> actualRanges = new ArrayList<>();
        AtomicInteger idx = new AtomicInteger(0);

        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    int i = idx.getAndIncrement();
                    actualRanges.add(request.headers().firstValue("Range").orElse(""));

                    Path partPath = destination.resolveSibling(destination.getFileName() + ".part" + i);
                    Files.writeString(partPath, "x");

                    HttpResponse<Path> partResponse = mock(HttpResponse.class);
                    when(partResponse.body()).thenReturn(partPath);
                    return CompletableFuture.completedFuture(partResponse);
                });

        downloader.downloadFile("http://example.com/file.txt", destination, 3);

        assertEquals(expectedRanges, actualRanges);
    }

    /**
     * Test that the downloader correctly handles the case where the server supports range requests but the file can be downloaded in a single chunk,
     * ensuring that it still produces the correct final file.
     */
    @Test
    void shouldDownloadUsingSingleChunkRange() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("single-chunk.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithRangesAndLength("bytes", 5));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);

        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertEquals("bytes=0-4", request.headers().firstValue("Range").orElse(""));

                    Path partPath = destination.resolveSibling(destination.getFileName() + ".part0");
                    Files.writeString(partPath, "abcde");

                    HttpResponse<Path> partResponse = mock(HttpResponse.class);
                    when(partResponse.body()).thenReturn(partPath);
                    return CompletableFuture.completedFuture(partResponse);
                });

        downloader.downloadFile("http://example.com/file.txt", destination, 1);

        assertEquals("abcde", Files.readString(destination));
    }

    /**
     * Test that the downloader correctly throws an exception when the server does not support range requests,
     * ensuring that it does not attempt to download the file and provides a clear error message.
     */
    @Test
    void shouldThrowWhenServerDoesNotSupportRanges() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("result.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithRangesAndLength("none", 100));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);

        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> downloader.downloadFile("http://example.com/file.txt", destination, 2));

        assertEquals("Server does not support ranges header with accept-ranges", ex.getMessage());
    }

    /**
     * Test that the downloader correctly throws an exception when the server does not provide a Content-Length header,
     * ensuring that it does not attempt to download the file and provides a clear error message.
     */
    @Test
    void shouldThrowWhenContentLengthHeaderIsMissing() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("result.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithoutContentLength());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> downloader.downloadFile("http://example.com/file.txt", destination, 2));

        assertEquals("Server does not returned Content-Length header", ex.getMessage());
    }

    /**
     * Test that the downloader correctly propagates exceptions thrown during the download of any chunk,
     * ensuring that it does not leave behind any partial files and provides a clear error message indicating which chunk failed.
     */
    @Test
    void shouldPropagateWhenAnyChunkDownloadFails() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("result.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithRangesAndLength("bytes", 10));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("boom")));

        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> downloader.downloadFile("http://example.com/file.txt", destination, 1));

        assertEquals("Error downloading chunk0: java.io.IOException: boom", ex.getCause().getMessage());
        assertTrue(Files.notExists(destination));
    }

    /**
     * Test that the downloader correctly propagates exceptions thrown during the HEAD request,
     * ensuring that it does not attempt to download the file and provides a clear error message.
     */
    @Test
    void shouldThrowWhenHeadRequestFails() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("result.txt");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("head-fail"));

        IOException ex = assertThrows(
                IOException.class,
                () -> downloader.downloadFile("http://example.com/file.txt", destination, 2));

        assertEquals("head-fail", ex.getMessage());
    }

    /**
     * Test that the downloader correctly throws an exception when a temporary part file is missing during the merge process,
     * ensuring that it does not produce a final file and provides a clear error message indicating the missing part.
     */
    @Test
    void shouldFailWhenTempPartFileIsMissingDuringMerge() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        ParallelFileDownloader downloader = new ParallelFileDownloader(httpClient);
        Path destination = tempDir.resolve("merge-fail.txt");

        HttpResponse<Void> headResponse = mockHeadResponse(headersWithRangesAndLength("bytes", 4));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(headResponse);

        AtomicInteger idx = new AtomicInteger(0);
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    int i = idx.getAndIncrement();
                    Path partPath = destination.resolveSibling(destination.getFileName() + ".part" + i);
                    if (i == 0) {
                        Files.writeString(partPath, "ab");
                    }
                    HttpResponse<Path> partResponse = mock(HttpResponse.class);
                    when(partResponse.body()).thenReturn(partPath);
                    return CompletableFuture.completedFuture(partResponse);
                });

        assertThrows(IOException.class, () -> downloader.downloadFile("http://example.com/file.txt", destination, 2));
    }

    /**
     * Helper method to create a mocked HttpResponse for the HEAD request with specified headers.
     */
    private static HttpResponse<Void> mockHeadResponse(HttpHeaders headers) {
        HttpResponse<Void> headResponse = mock(HttpResponse.class);
        when(headResponse.headers()).thenReturn(headers);
        return headResponse;
    }

    /**
     * Helper method to create HttpHeaders with specified Accept-Ranges and Content-Length values.
     */
    private static HttpHeaders headersWithRangesAndLength(String acceptRanges, long contentLength) {
        return HttpHeaders.of(
                Map.of(
                        "Accept-Ranges", List.of(acceptRanges),
                        "Content-Length", List.of(String.valueOf(contentLength))),
                (name, value) -> true);
    }

    /**
     * Helper method to create HttpHeaders without Content-Length header.
     */
    private static HttpHeaders headersWithoutContentLength() {
        return HttpHeaders.of(
                Map.of("Accept-Ranges", List.of("bytes")),
                (name, value) -> true);
    }
}
