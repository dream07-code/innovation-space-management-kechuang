# Judge0 语言种子覆盖（1.13.1）
#
# 挂载到容器的 /api/db/seeds.rb，Judge0 每次启动都会执行 `rails db:seed`，
# 因此这里必须完成原 seeds.rb 的全部工作，再追加运行时语言的 JVM/V8 参数。
#
# 背景：cgroup v2 主机上只能用 isolate 的 rlimit 模式（RLIMIT_AS 限制虚拟地址空间）。
# JVM 和 Node 启动时要预留 1GB 以上虚拟地址，默认参数下会被直接杀掉，表现为
# “编译错误 / 运行错误”。这里给它们加上堆上限参数，让真实内存仍然很小，
# 只是允许预留更大的虚拟地址空间（deploy/.env 的 OJ_RUNTIME_MEMORY_LIMIT_KB）。
require_relative 'languages/archived'
require_relative 'languages/active'

JAVA_FLAGS = '-Xmx128m -Xss16m -XX:CompressedClassSpaceSize=64m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m'.freeze
NODE_FLAGS = '--max-old-space-size=128'.freeze

ActiveRecord::Base.transaction do
  Language.unscoped.delete_all
  @languages.each_with_index do |language, index|
    Language.create(
      id: language[:id],
      name: language[:name],
      is_archived: language[:is_archived],
      source_file: language[:source_file],
      compile_cmd: language[:compile_cmd],
      run_cmd: language[:run_cmd],
    )
  end

  # Java (OpenJDK 13.0.1)：编译和运行都限制堆与元空间，避免 JVM 预留 1GB 类空间。
  Language.where(id: 62).update_all(
    compile_cmd: "/usr/local/openjdk13/bin/javac -J-Xmx128m -J-XX:CompressedClassSpaceSize=64m %s Main.java",
    run_cmd: "/usr/local/openjdk13/bin/java #{JAVA_FLAGS} Main",
  )

  # JavaScript (Node.js 12)：同上，限制 V8 老生代大小。
  Language.where(id: 63).update_all(
    run_cmd: "/usr/local/node-12.14.0/bin/node #{NODE_FLAGS} script.js",
  )
end
