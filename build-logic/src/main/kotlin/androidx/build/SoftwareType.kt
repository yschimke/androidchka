package androidx.build

/**
 * Minimal stub of androidx.build.SoftwareType — captures only the symbols build files reference.
 * Values are sentinels; the overlay's [AndroidXPlugin] does not act on them.
 */
open class SoftwareType(val name: String) {
    companion object {
        @JvmField val ANNOTATION_PROCESSOR = SoftwareType("ANNOTATION_PROCESSOR")
        @JvmField val ANNOTATION_PROCESSOR_UTILS = SoftwareType("ANNOTATION_PROCESSOR_UTILS")
        @JvmField val GRADLE_PLUGIN = SoftwareType("GRADLE_PLUGIN")
        @JvmField val OTHER_CODE_PROCESSOR = SoftwareType("OTHER_CODE_PROCESSOR")
        @JvmField val LINT = SoftwareType("LINT")
        @JvmField val STANDALONE_PUBLISHED_LIBRARY = SoftwareType("STANDALONE_PUBLISHED_LIBRARY")
        @JvmField val PUBLISHED_LIBRARY = SoftwareType("PUBLISHED_LIBRARY")
        @JvmField val PUBLISHED_LIBRARY_ONLY_USED_BY_KOTLIN_CONSUMERS =
            SoftwareType("PUBLISHED_LIBRARY_ONLY_USED_BY_KOTLIN_CONSUMERS")
        @JvmField val PUBLISHED_KOTLIN_ONLY_LIBRARY = SoftwareType("PUBLISHED_KOTLIN_ONLY_LIBRARY")
        @JvmField val PUBLISHED_NATIVE_LIBRARY = SoftwareType("PUBLISHED_NATIVE_LIBRARY")
        @JvmField val PUBLISHED_TEST_LIBRARY = SoftwareType("PUBLISHED_TEST_LIBRARY")
        @JvmField val PUBLISHED_KOTLIN_ONLY_TEST_LIBRARY = SoftwareType("PUBLISHED_KOTLIN_ONLY_TEST_LIBRARY")
        @JvmField val INTERNAL_LIBRARY = SoftwareType("INTERNAL_LIBRARY")
        @JvmField val INTERNAL_LIBRARY_WITH_API_TASKS = SoftwareType("INTERNAL_LIBRARY_WITH_API_TASKS")
        @JvmField val SNAPSHOT_ONLY_LIBRARY = SoftwareType("SNAPSHOT_ONLY_LIBRARY")
        @JvmField val SNAPSHOT_ONLY_LIBRARY_WITH_API_TASKS = SoftwareType("SNAPSHOT_ONLY_LIBRARY_WITH_API_TASKS")
        @JvmField val INTERNAL_HOST_TEST_LIBRARY = SoftwareType("INTERNAL_HOST_TEST_LIBRARY")
        @JvmField val INTERNAL_TEST_LIBRARY = SoftwareType("INTERNAL_TEST_LIBRARY")
        @JvmField val INTERNAL_GRADLE_PLUGIN = SoftwareType("INTERNAL_GRADLE_PLUGIN")
        @JvmField val SAMPLES = SoftwareType("SAMPLES")
        @JvmField val IDE_PLUGIN = SoftwareType("IDE_PLUGIN")
        @JvmField val APP = SoftwareType("APP")
        @JvmField val BENCHMARK_APP = SoftwareType("BENCHMARK_APP")
        @JvmField val TEST_APPLICATION = SoftwareType("TEST_APPLICATION")
        @JvmField val UNSET = SoftwareType("UNSET")
    }
}
