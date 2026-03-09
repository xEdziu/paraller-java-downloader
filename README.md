# Parallel Java Downloader

This is a repository that contains the solution to recruitment task for and internship position at **JetBrains**.

The task was to implement a file downloader which has the ability to download chunks of a file in parallel using Java or Kotlin.

## Setting Up

1. Clone the repository to your local machine.
2. Run the docker container with web server to locally host the file to be downloaded:
   ```bash
   docker run --rm -p 8080:80 -v /path/to/your/local/directory:/usr/local/apache2/htdocs/ httpd:latest
   ```
    > [!NOTE] 
   > Replace `/path/to/your/local/directory` with the actual path to the directory containing the file you want to download.
3. Update the `url` variable in the `Main` class to point to the file you want to download. For example:
   ```java
   String url = "http://localhost:8080/yourfile.ext";
    ```
4. Run the `Main` class to start the download process.
5. The downloaded file will be saved in the same directory as the `Main` class with the name `downloaded_file.ext`.

## Testing

To run the tests, you can use the following command in your terminal:

```bash
mvn test
```

Tests are implemented using JUnit and Mockito.
There are 8 test cases that test [ParallelFileDownloader](src/main/java/ParallelFileDownloader.java) class:

1. `shouldDownloadFileInChunksAndMergeInOrder`: Test that the downloader correctly splits the file into chunks, sends the appropriate range requests, and merges the downloaded parts in the correct order to produce the final file.
2. `shouldCalculateRangesCorrectlyWhenContentLengthIsNotDivisibleByChunkCount`: Test that the downloader correctly calculates byte ranges when the content length is not perfectly divisible by the number of chunks, ensuring that the last chunk correctly includes any remaining bytes.
3. `shouldDownloadUsingSingleChunkRange`: Test that the downloader correctly handles the case where the server supports range requests but the file can be downloaded in a single chunk, ensuring that it still produces the correct final file.
4. `shouldThrowWhenServerDoesNotSupportRanges`: Test that the downloader correctly throws an exception when the server does not support range requests, ensuring that it does not attempt to download the file and provides a clear error message.
5. `shouldThrowWhenContentLengthHeaderIsMissing`: Test that the downloader correctly throws an exception when the server does not provide a Content-Length header, ensuring that it does not attempt to download the file and provides a clear error message.
6. `shouldPropagateWhenAnyChunkDownloadFails`: Test that the downloader correctly propagates exceptions thrown during the download of any chunk, ensuring that it does not leave behind any partial files and provides a clear error message indicating which chunk failed.
7. `shouldThrowWhenHeadRequestFails`: Test that the downloader correctly propagates exceptions thrown during the HEAD request, ensuring that it does not attempt to download the file and provides a clear error message.
8. `shouldFailWhenTempPartFileIsMissingDuringMerge`: Test that the downloader correctly throws an exception when a temporary part file is missing during the merge process, ensuring that it does not produce a final file and provides a clear error message indicating the missing part.
