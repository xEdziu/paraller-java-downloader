import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ParallelFileDownloader {

    private final HttpClient httpClient;

    // Injection of httpClient through constructor
    public ParallelFileDownloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Builder of default http client in production environment
    public static HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public void downloadFile(String targetUrl, Path destination, int numberOfChunks) throws Exception {
        //Build and send HEAD request to receive metadata
        HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        //Synchronous method - without metadata we don't want to go any further
        HttpResponse<Void> headResponse = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());

        //Verify if the server is accepting Range requests
        String acceptsRanges = headResponse.headers().firstValue("Accept-Ranges").orElse("none");
        if (!"bytes".equals(acceptsRanges)) {
            throw new UnsupportedOperationException("Server does not support ranges header with accept-ranges");
        }

        // Get content length in bytes
        long contentLength = headResponse.headers().firstValueAsLong("Content-Length")
                .orElseThrow(() -> new IllegalStateException("Server does not returned Content-Length header"));

        //Splitting the file for the byte ranges for individual threads
        List<ChunkInfo> chunks = calculateRanges(contentLength, numberOfChunks);

        // Display logs - dev info only
        System.out.println("Size of the file: " + contentLength + " bytes. Split into: " + numberOfChunks + " chunks.");
        for (ChunkInfo chunk : chunks) {
            System.out.println("Chunk: " + chunk.index + ": bytes " + chunk.startByte + " - " + chunk.endByte);
        }

        // List for Tasks and tmp files paths
        List<CompletableFuture<Path>> downloadTasks = new ArrayList<>();
        List<Path> tempFiles = new ArrayList<>();

        System.out.println("Starting parallel download...");
        for (ChunkInfo chunk : chunks) {
            // Create unique temp file path for each fragment
            Path tempFile = destination.resolveSibling(destination.getFileName() + ".part" + chunk.index);
            tempFiles.add(tempFile);

            //Create GET request with Range header
            HttpRequest chunkRequest = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .GET()
                    .header("Range", "bytes=" + chunk.startByte + "-" + chunk.endByte)
                    .build();

            //Send async request
            //We use BodyHandlers.ofFile which omit JVM heap, streaming data straight to our storage
            CompletableFuture<Path> task = httpClient.sendAsync(chunkRequest, HttpResponse.BodyHandlers.ofFile(tempFile))
                    .thenApply(HttpResponse::body)
                    .exceptionally(ex -> {
                        System.err.println("Error downloading chunk" + chunk.index + ": " + ex.getMessage());
                        throw new RuntimeException("Error downloading chunk" + chunk.index + ": " + ex.getMessage());
                    });
            downloadTasks.add(task);
        }

        // We wait for downloading all chunks
        System.out.println("Waiting for downloading all chunks.");

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(downloadTasks.toArray(CompletableFuture[]::new));

        allFutures.join();

        System.out.println("All chunks downloaded.");
    }

    //Support method responsible for mathematical slicing the file
    private List<ChunkInfo> calculateRanges(long contentLength, int numberOfChunks) {
        List<ChunkInfo> ranges = new ArrayList<>();
        long chunkSize = contentLength / numberOfChunks;

        for (int i = 0; i < numberOfChunks; i++) {
            long startByte = i * chunkSize;
            //Normally, the end of range is start + chunkSize -1
            //But for the last chunk we absolutely end on the last byte of the file (contentLength - 1)
            long endByte = (i == numberOfChunks - 1) ? contentLength - 1 : (startByte + chunkSize - 1);

            ranges.add(new ChunkInfo(startByte, endByte, i));
        }
        return ranges;
    }

    //Support class containing metadata of single chunk
    record ChunkInfo(long startByte, long endByte, int index) { }
}
