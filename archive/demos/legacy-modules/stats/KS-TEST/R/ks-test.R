require(graphics)

#
# Some technical background.
# 
#    http://stats.stackexchange.com/questions/113032/ks-test-and-ks-boot-exact-p-values-and-ties
#
#

#
#
#distrData <- read.table('./../../data/out/NRCTESTS/dump-raw/1000_-100.0_100.0_testdata.tsv', header=FALSE, sep="\t", skip=9)
#
inputfile = "/GITHUB/Hadoop 2.TS/data/out/NRCTESTS/dump-raw/1000_-100.0_100.0_testdata.tsv"
distrData <- read.delim2( inputfile, header=FALSE, comment.char="#")
colnames(distrData) <- c("id", "x1", "x2", "note")
str(distrData)


x1 <- distrData$x1
x2 <- distrData$x2

# Do x and y come from the same distribution?
tr <- ks.test(x1, x2)
tr$p.value

plot(ecdf(x1), xlim = range(c(x1, x2)))
plot(ecdf(x2), add = TRUE, lty = "dashed")

View(distrData)



