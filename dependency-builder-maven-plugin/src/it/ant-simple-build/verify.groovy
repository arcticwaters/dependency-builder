def repo = new File(basedir, "target/dependency-builder/repository");
def files = [
	"com/example/ant-simple/0.0.1/ant-simple-0.0.1.jar",
	"com/example/ant-simple/0.0.1/ant-simple-0.0.1.pom",
];

for (def file : files) {
	def touchFile = new File(repo, file);
	println "Checking for existence: " + touchFile;
	assert touchFile.isFile();
}