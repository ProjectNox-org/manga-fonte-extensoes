import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import importlib

gbm = importlib.import_module("generate-build-matrices")
get_module_list = gbm.get_module_list
CORE_FILES_REGEX = gbm.CORE_FILES_REGEX
EXTENSION_REGEX = gbm.EXTENSION_REGEX
MULTISRC_LIB_REGEX = gbm.MULTISRC_LIB_REGEX
LIB_REGEX = gbm.LIB_REGEX



def evaluate_publish_condition(
    prepare_result: str,
    publish_required: bool,
    has_build_modules: bool,
    build_result: str,
) -> bool:
    """Exact reproduction of the GitHub Actions publish job 'if' expression:
    needs.prepare.result == 'success' &&
    needs.prepare.outputs.publish_required == 'true' &&
    (
      needs.prepare.outputs.has_build_modules != 'true' ||
      needs.build.result == 'success'
    ) &&
    needs.build.result != 'failure' &&
    needs.build.result != 'cancelled'
    """
    if prepare_result != "success":
        return False
    if not publish_required:
        return False
    if has_build_modules and build_result != "success":
        return False
    if build_result in ("failure", "cancelled"):
        return False
    return True


class PublishConditionTest(unittest.TestCase):
    def test_scenario_a_only_new(self):
        # NEW > 0, UPDATED = 0, REMOVED = 0
        has_build_modules = True
        has_deletions = False
        publish_required = has_build_modules or has_deletions
        build_result = "success"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertTrue(should_publish, "Scenario A (only NEW) must publish")

    def test_scenario_b_only_updated(self):
        # NEW = 0, UPDATED > 0, REMOVED = 0
        has_build_modules = True
        has_deletions = False
        publish_required = has_build_modules or has_deletions
        build_result = "success"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertTrue(should_publish, "Scenario B (only UPDATED) must publish")

    def test_scenario_c_only_removed(self):
        # NEW = 0, UPDATED = 0, REMOVED > 0
        # Build job is skipped because has_build_modules is False
        has_build_modules = False
        has_deletions = True
        publish_required = has_build_modules or has_deletions
        build_result = "skipped"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertTrue(should_publish, "Scenario C (only REMOVED) must publish even when build is skipped")

    def test_scenario_d_new_and_updated(self):
        # NEW > 0, UPDATED > 0, REMOVED = 0
        has_build_modules = True
        has_deletions = False
        publish_required = has_build_modules or has_deletions
        build_result = "success"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertTrue(should_publish, "Scenario D (NEW + UPDATED) must publish")

    def test_scenario_e_new_updated_and_removed(self):
        # NEW > 0, UPDATED > 0, REMOVED > 0
        has_build_modules = True
        has_deletions = True
        publish_required = has_build_modules or has_deletions
        build_result = "success"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertTrue(should_publish, "Scenario E (NEW + UPDATED + REMOVED) must publish")

    def test_scenario_f_no_changes(self):
        # No real changes
        has_build_modules = False
        has_deletions = False
        publish_required = has_build_modules or has_deletions
        build_result = "skipped"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertFalse(should_publish, "Scenario F (no changes) must NOT publish")

    def test_build_failed_blocks_publish(self):
        has_build_modules = True
        has_deletions = True
        publish_required = True
        build_result = "failure"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertFalse(should_publish, "Failed build must block publish")

    def test_build_cancelled_blocks_publish(self):
        has_build_modules = True
        has_deletions = False
        publish_required = True
        build_result = "cancelled"

        should_publish = evaluate_publish_condition(
            prepare_result="success",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertFalse(should_publish, "Cancelled build must block publish")

    def test_prepare_failed_blocks_publish(self):
        has_build_modules = False
        has_deletions = True
        publish_required = True
        build_result = "skipped"

        should_publish = evaluate_publish_condition(
            prepare_result="failure",
            publish_required=publish_required,
            has_build_modules=has_build_modules,
            build_result=build_result,
        )
        self.assertFalse(should_publish, "Failed prepare must block publish")


class GenerateBuildMatricesTest(unittest.TestCase):
    def test_regexes(self):
        self.assertTrue(CORE_FILES_REGEX.search("gradle/build-logic/src/main/kotlin/Constant.kt"))
        self.assertTrue(CORE_FILES_REGEX.search("lint-rules/src/main/kotlin/Rule.kt"))
        self.assertTrue(CORE_FILES_REGEX.search("settings.gradle.kts"))
        self.assertTrue(CORE_FILES_REGEX.search("build.gradle.kts"))
        self.assertFalse(CORE_FILES_REGEX.search("src/pt/sample/build.gradle.kts"))

        m = EXTENSION_REGEX.search("src/pt/sample/build.gradle.kts")
        self.assertIsNotNone(m)
        self.assertEqual(m.group("lang"), "pt")
        self.assertEqual(m.group("extension"), "sample")

        m = MULTISRC_LIB_REGEX.search("lib-multisrc/sampletheme/build.gradle.kts")
        self.assertIsNotNone(m)
        self.assertEqual(m.group("multisrc"), "sampletheme")

        m = LIB_REGEX.search("lib/imagedecoder/build.gradle.kts")
        self.assertIsNotNone(m)
        self.assertEqual(m.group("lib"), "imagedecoder")

    def test_deferred_aurora_units_excluded_from_matrix_and_deletions_on_core_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir(parents=True)
            (root / ".github/sync-deferred-migrations.json").write_text(
                json.dumps({
                    "themes": {
                        "aurora": {
                            "reason": "functional conflict: Aurora.kt",
                            "units": [
                                "src/pt/leitordemangas",
                                "src/pt/manganyx",
                                "src/pt/mangasbrasuka",
                                "src/pt/mugiwarasoficial",
                            ],
                        }
                    }
                })
            )
            for u in (
                "src/pt/leitordemangas",
                "src/pt/manganyx",
                "src/pt/mangasbrasuka",
                "src/pt/mugiwarasoficial",
                "src/pt/xxxyaoi",
            ):
                (root / u).mkdir(parents=True)
                (root / u / "build.gradle.kts").write_text('theme = "aurora"\n' if "xxx" not in u else "")

            orig_cwd = os.getcwd()
            try:
                os.chdir(root)
                with patch.object(gbm, "run_command", return_value="M\tcore/src/main/kotlin/keiyoushi/source/KeiSource.kt\n"):
                    modules, deleted = get_module_list("a..b")
                all_mods, all_dels = gbm.get_all_modules()
            finally:
                os.chdir(orig_cwd)

            for ext in ("leitordemangas", "manganyx", "mangasbrasuka", "mugiwarasoficial"):
                self.assertNotIn(f":src:pt:{ext}", modules)
                self.assertNotIn(f"pt.{ext}", deleted)
                self.assertNotIn(f":src:pt:{ext}", all_mods)
                self.assertNotIn(f"pt.{ext}", all_dels)
            self.assertIn(":src:pt:xxxyaoi", modules)
            self.assertIn("pt.xxxyaoi", deleted)


if __name__ == "__main__":
    unittest.main()
