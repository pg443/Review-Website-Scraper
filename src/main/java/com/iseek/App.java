package com.iseek;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequests;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/* Program to fetch reviews from cochranelibrary.com
 * This program uses multithreading along with async Apache HttpClient
 * The program also uses Jsoup to parse the document and get links and other data from it.
 */
public class App {

    public static void main(final String[] args) {
        /*
         * Main function fetches the main page of Cochrane library and then passes the
         * page html to getReviewForAllTopics function to get the review from all the
         * topics listed on
         * www.cochranelibrary.com//home/topic-and-review-group-list.html?page=topic
         */
        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom().setSoTimeout(Timeout.ofSeconds(15)).build();
        final PoolingAsyncClientConnectionManager poolMgr = new PoolingAsyncClientConnectionManager();
        poolMgr.setMaxTotal(30); // Limiting the number of parallel requests to 30 so that it does not overwhelm
                                 // the server
        poolMgr.setDefaultMaxPerRoute(100);
        RequestConfig config = RequestConfig.custom().setCircularRedirectsAllowed(true).build();

        final CloseableHttpAsyncClient client = HttpAsyncClientBuilder.create().setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.90 Safari/537.36")
                .setIOReactorConfig(ioReactorConfig).setConnectionManager(poolMgr).setDefaultRequestConfig(config)
                .build();

        client.start();

        final HttpHost target = new HttpHost("https", "www.cochranelibrary.com");
        final String uri = "/home/topic-and-review-group-list.html?page=topic";
        final SimpleHttpRequest httpget = SimpleHttpRequests.get(target, uri);

        try {
            System.out.println("Getting main page -> " + httpget.getUri());
            final Future<SimpleHttpResponse> future = client.execute(httpget, null);
            final SimpleHttpResponse response = future.get();
            System.out.println("Finished getting the main page.");
            getReviewsForAllTopics(response.getBodyText(), client);
        } catch (final Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("Shutting down");
            client.close(CloseMode.GRACEFUL);
        }
    }

    public static void getReviewsForAllTopics(final String responseBody, final CloseableHttpAsyncClient client) {
        /*
         * This function gets reviews from all the topics listed in the hoe page. It
         * creates thread for each topic Each thread handles data collection for all the
         * pages of that topic
         */
        final Document doc = Jsoup.parse(responseBody);
        final Elements links = doc.getElementsByClass("browse-by-list-item");
        try {
            final FileOutputStream file_writer = new FileOutputStream("cochrane_reviews.txt");
            final FileChannel file = file_writer.getChannel();
            final GetThread[] threads = new GetThread[links.size()];
            for (Integer i = 0; i < links.size(); i++) {
                final Element a = links.get(i).child(0);
                threads[i] = new GetThread(a, client, file);
                threads[i].setName(i.toString());
                System.out.println("Requesting");
            }
            System.out.println("Finished creating requests. Waiting for completion.");
            for (GetThread thread : threads) {
                thread.start();
            }
            for (GetThread thread : threads) {
                thread.join();
            }

            System.out.println("All requests completed.");
            file_writer.close();
        } catch (final Exception e) {
            System.out.println("Request failed");
        } finally {
            System.out.println("Getting out of the review");
        }
    }

    static class GetThread extends Thread {
        /*
         * This is the thread handler for each topic. This thread handler spins
         * additional thread to get all the pages One thread per page.
         * 
         * Total number of created thread = links shown in the pagination
         */
        private CloseableHttpAsyncClient client;
        private FileChannel writer;
        private Element pageLink;
        private HttpContext httpContext;

        public GetThread(final Element page_ref, final CloseableHttpAsyncClient client, final FileChannel writer) {
            this.client = client;
            this.writer = writer;
            this.pageLink = page_ref;
            httpContext = new BasicHttpContext();
            BasicCookieStore cookieStore = new BasicCookieStore();
            httpContext.setAttribute(HttpClientContext.COOKIE_STORE, cookieStore);
        }

        @Override
        public void run() {
            final String page_url = pageLink.attributes().get("href");
            final SimpleHttpRequest httpget = SimpleHttpRequests.get(page_url);
            Future<SimpleHttpResponse> future = client.execute(httpget, httpContext, null);
            try {
                SimpleHttpResponse response = future.get();
                final Document doc = Jsoup.parse(response.getBodyText());
                final Elements links = doc.getElementsByClass("search-results-item-body");
                List<String> metadata = new ArrayList<>();
                for (final Element i : links)
                    metadata.add(parseReview(i));
                final Element footer = doc.getElementsByClass("search-results-footer").first();
                final Elements pages = footer != null ? footer.getElementsByClass("pagination-page-list-item") : null;
                if (pages != null && pages.size() > 0) {
                    final GetPageNumberThread[] page_threads = new GetPageNumberThread[pages.size() - 1];

                    for (int i = 0; i < pages.size(); i++) {
                        if (pages.get(i).hasClass("active"))
                            continue;
                        Element inner_url = pages.get(i).getElementsByTag("a").first();
                        page_threads[i - 1] = new GetPageNumberThread(inner_url, httpContext, client);

                        if (i == pages.size() - 1)
                            page_threads[i - 1].setLast();
                    }

                    for (GetPageNumberThread t : page_threads)
                        t.start();

                    for (GetPageNumberThread t : page_threads)
                        t.join();

                    for (GetPageNumberThread t : page_threads) {
                        List<String> a = t.getReviews();

                        for (String data : a)
                            metadata.add(data);
                    }
                }
                writeToFile(writer, metadata, pageLink.text());
            } catch (Exception e) {
                System.out.println("Error occured in thread for--> " + pageLink.text());
                e.printStackTrace();
            }
        }
    }

    static class GetPageNumberThread extends Thread {
        /*
         * This thread handler handles individual pages of a topic The last thread
         * creates additional threads to get the values from link appeared only after
         * navigating to the last page. New thread creation is recursive, so every last
         * thread of pagination created more threads until all the reviews are
         * collected.
         */
        private CloseableHttpAsyncClient client;
        private Element pageLink;
        private volatile List<String> reviews;
        private HttpContext httpContext;
        private Boolean lastThread = false;

        public GetPageNumberThread(final Element page_ref, HttpContext httpContext,
                final CloseableHttpAsyncClient client) {
            this.client = client;
            this.pageLink = page_ref;
            this.httpContext = httpContext;
        }

        @Override
        public void run() {
            final String page_url = pageLink.attributes().get("href");
            final SimpleHttpRequest httpget = SimpleHttpRequests.get(page_url);
            Future<SimpleHttpResponse> future = client.execute(httpget, httpContext, null);
            try {
                SimpleHttpResponse response = future.get();
                final Document doc = Jsoup.parse(response.getBodyText());
                final Elements links = doc.getElementsByClass("search-results-item-body");
                reviews = new ArrayList<>();
                for (final Element i : links)
                    reviews.add(parseReview(i));

                if (lastThread) {
                    final Elements footer = doc.getElementsByClass("search-results-footer");
                    getNext(footer);
                }

            } catch (Exception e) {
                System.out.println("Error occured in thread for --> " + pageLink.text());
                e.printStackTrace();
            }
        }

        public List<String> getReviews() {
            /*
             * Getter function to collect the reviews list
             */
            return reviews;
        }

        private void getNext(Elements footer) throws Exception {
            /*
             * Function to fetch next pages whose link only appears around the end of the
             * pagination link from GetThread threads
             */
            Elements pages = getRemainingLinks(footer);
            if (pages == null || pages.isEmpty())
                return;

            final GetPageNumberThread[] page_threads = new GetPageNumberThread[pages.size()];
            for (int i = 0; i < pages.size(); i++) {
                Element inner_url = pages.get(i).getElementsByTag("a").first();
                page_threads[i] = new GetPageNumberThread(inner_url, httpContext, client);

                if (i == pages.size() - 1)
                    page_threads[i].setLast();
            }

            for (GetPageNumberThread t : page_threads)
                t.start();

            for (GetPageNumberThread t : page_threads)
                t.join();

            for (GetPageNumberThread t : page_threads) {
                List<String> a = t.getReviews();

                for (String data : a)
                    reviews.add(data);
            }
        }

        private Elements getRemainingLinks(Elements footer) {
            /*
             * Helper function returning the next unvisitied links
             */
            if (footer == null || footer.isEmpty())
                return null;

            Elements links = footer.first().getElementsByClass("pagination-page-list-item");

            if (links == null || links.isEmpty())
                return null;

            Elements returnList = new Elements();
            Boolean start = false;
            for (Element i : links) {
                if (start) {
                    returnList.add(i);
                } else {
                    if (i.hasClass("active")) {
                        start = true;
                    }
                }
            }
            return returnList;
        }

        public void setLast() {
            /*
             * Helper function to mark the last thread of a pagination
             */
            lastThread = true;
        }
    }

    public static void writeToFile(final FileChannel file, List<String> data, final String page_title) {
        /*
         * Function to write all reviews of a topic onto the file cochrane_reviews.txt
         */
        synchronized (file) {
            try (FileLock lock = file.lock()) {
                file.write(str2ByteBuffer(page_title + System.lineSeparator()));
                for (final String str : data)
                    file.write(str2ByteBuffer(str + System.lineSeparator()));
                file.write(str2ByteBuffer(System.lineSeparator() + System.lineSeparator()));
            } catch (final ClosedChannelException e) {
                System.out.println("Channel has been closed");
            } catch (OverlappingFileLockException e) {
                System.err.println("Overlapping file locks. Trying again!");
                writeToFile(file, data, page_title);
            } catch (final IOException e) {
                System.err.println("File blocking failed");
            }
        }
    }

    private static ByteBuffer str2ByteBuffer(final String str) {
        /*
         * Function to convert string into ByteBuffer
         */
        return ByteBuffer.wrap(str.getBytes());
    }

    protected static String parseReview(final Element i) {
        /*
         * Function to parse one review and collect important information
         */
        String metadata_line = "";
        final Element a = i.child(0).child(0);
        final String review_url = a.attributes().get("href");
        metadata_line += "https://www.cochranelibrary.com" + review_url + "|";

        final String title = a.text();
        metadata_line += title + "|";

        final String authors = i.getElementsByClass("search-result-authors").text();
        metadata_line += authors + "|";

        final String date = i.getElementsByClass("search-result-date").text();
        metadata_line += date;
        return metadata_line;
    }
}
