Authored By: Prasenjit Gaurav
# Review-Website-Scraper
a program to crawl through the Cochrane Library’s review site and collect review from there. Collected data are written to a single text file (e.g., cochrane_reviews.txt). This file uses pipes “|” to delimit the data. The reviews are separated by newlines.

Date: 05/08/2021

Goal:
Write a program to crawl through the Cochrane Library’s review site and collect review from there. Write the collected data to a single text file (e.g., cochrane_reviews.txt). This file must use pipes “|” to delimit the data. The reviews are separated by newlines.
Design description:
The program is using Apache’s CloseableHttpAsyncClient to connect to the Cochrane Library’s website for its high availability. It also uses PoolingAsyncClientConnectionManager to manage the request pool which helped in making sure the Cochrane’s server does not throttle the program’s parallel requests. 
 
Main function gets the home page of the reviews. It then passes the HTML file to getReviewForAllTopics function. 
getReviewForAllTopics function parses the HTML file using JSoup library and creates GetThread threads for each topic. 
Each GetThread threads download the first page of the review page for their topic and parse it collecting important data like URL, title, authors, and date of the review using parseReview function. Then it gets all the links of the next pages seen in the pagination. Then for each of the page links, it creates GetPageNumberThread threads marking the last link’s thread as last. It then collects review data from all the threads it created by calling function getReviews of each completed and joined threads and call the writeToFile function to write it in the file.
Each GetPageNumberThread thread download their review page, parse it and get all the reviews. Thread marked last is the thread which is the last page number of a given page’s pagination. Last thread sees more page number when it loads it page (Figure 3). So it is last thread’s task is to create more thread to load rest of the pages. Last threads recursively created GetPageNumberThread threads for the new links until all the links are visited. It creates new threads by calling the function getNext.
 
getNext function gets all the newly visible links by calling private utility function getRemainingLinks. It then creates GetPageNumberThread threads and collects their review data using getReviews.
writeToFile function is used to write the review data to the file. To create thread-safety, this function tries to synchronize the writes using file lock. Once file lock is acquired by a GetThread thread, they dump all the data for their topic into the file. The first line of the dump is the topic name followed by newline. After that, each of the reviews are dumped followed by newline. Each review has data URL, title, authors, and date of the review; each delimited by pipe character (“|”). At the end of each topics review dump, there are two additional newlines added before dumping next topics’ data. 
There was also intervention, sub-review, free-access, and other data under each metadata about most reviews which could also be harnessed using the program easily.
