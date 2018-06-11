# makefile for figwheel.main
PROJECT_FILES=../figwheel-core/project.clj ../figwheel-repl/project.clj project.clj
DOC_FILES=README.md helper-content/*.md
DEPS_FILES=deps.edn

# sed commands for changing version strings
CHANGE_LEIN_DEPS=sed -i '' -e "s|\[com.bhauman/figwheel-\([^[:space:]]*\) \".*\"\]|[com.bhauman/figwheel-\1 \"$(VERSION)\"]|g"
CHANGE_TOOLS_DEPS=sed -i '' -e "s|com.bhauman/figwheel-\([^[:space:]]*\) {:mvn/version \".*\"}|com.bhauman/figwheel-\1 {:mvn/version \"$(VERSION)\"}|g"
MARKDOWN=ruby scripts/kram.rb

deps-version:
	sed -i '' -e "s|defproject com.bhauman/figwheel-\([^[:space:]]*\) \".*\"|defproject com.bhauman/figwheel-\1 \"$(VERSION)\"|g" $(PROJECT_FILES)
	$(CHANGE_LEIN_DEPS) $(PROJECT_FILES)
	$(CHANGE_TOOLS_DEPS) $(DEPS_FILES)

docs-version:
	$(CHANGE_LEIN_DEPS) $(DOC_FILES)
	$(CHANGE_TOOLS_DEPS) $(DOC_FILES)

snapshot-version: deps-version

release-version: deps-version docs-version

helper-docs:
	$(MARKDOWN) helper-content/*

opt-docs:
	clojure -Adocs

docs: helper-docs opt-docs

helper:
	clojure -Abuild-helper

install: docs
	pushd ../figwheel-core; lein install; popd; pushd ../figwheel-repl; lein install; popd; lein install

testit:
	lein test

deploy: docs helper install test
	pushd ../figwheel-core; lein deploy clojars; popd; pushd ../figwheel-repl; lein deploy clojars; popd; lein deploy clojars
