def repo = new File(basedir, "target/dependency-builder/repository");
def files = [
	"com/example/multi-module/0.0.1/multi-module-0.0.1.pom",
	"com/example/module1/0.0.1/module1-0.0.1.pom",
	"com/example/module1/0.0.1/module1-0.0.1.jar",
	"com/example/module2/0.0.1/module2-0.0.1.pom",
	"com/example/module2/0.0.1/module2-0.0.1.jar",
];

for (def file : files) {
	def touchFile = new File(repo, file);
	println "Checking for existence: " + touchFile;
	assert touchFile.isFile();
}