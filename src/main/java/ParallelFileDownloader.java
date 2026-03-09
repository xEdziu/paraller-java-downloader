import java.net.http.HttpClient;
import java.time.Duration;

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
}
