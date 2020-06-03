#GithubRank challenge

Write a server that will return all contributors to a specific organization on GitHub ordered by their contributions.
There are can be multiple pages with results. And you have to consider rate limiting of GitHub.

###To run locally
Set Github token as an environment variable of name GH_TOKEN

Run server with command:
sbt run

###How to test
Server listens on port 8080.
Call server with url /org/ORGANIZATION/contributors specifying any organization that exist on GitHub. 
For example: http://localhost:8080/org/akka/contributors