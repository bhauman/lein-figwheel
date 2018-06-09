require 'kramdown'

for filename in ARGV do
  contents = File.read(filename)
  doc = Kramdown::Document.new(contents, {input: 'GFM', hard_wrap: false})
  new_name = filename.split("/").last.split(".").first + ".html"
  File.write('helper-resources/public/com/bhauman/figwheel/helper/content/' + new_name , doc.to_html)
end
