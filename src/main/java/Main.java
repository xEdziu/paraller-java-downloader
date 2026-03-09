import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    public static final String DOWNLOAD_URL = "http://localhost:8080/my-test-file.txt";
    public static final Path TARGET_DIR = Paths.get("src/main/java/my-test-file.txt");
    public static final Integer NUMBER_OF_CHUNKS = 4;

    public static void main(String[] args) {

        System.out.println("Starting Downloader");
        ParallelFileDownloader parallelFileDownloader = new ParallelFileDownloader(
                ParallelFileDownloader.createDefaultHttpClient()
        );
        System.out.println("Downloading files...");
        try {
            parallelFileDownloader.downloadFile(DOWNLOAD_URL, TARGET_DIR, NUMBER_OF_CHUNKS);
        } catch (Exception e) {
            System.out.println("Error downloading file: " + e.getMessage());
        }
        System.out.println("Finished Downloader");
    }
}
