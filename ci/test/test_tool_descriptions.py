from __future__ import annotations

import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "ci" / "script"))

from check_tool_descriptions import (  # noqa: E402
    collect_literals,
    enclosing_call,
)


REGISTRATION_TEMPLATE = """handler.registerTool(
        name = "{name}",
        descriptionGenerator = {{ {body} }},
        executor = {{ tool -> ToolGetter.getBrowserSessionTools(context).invoke(tool) }}
)
"""


def registration(name: str, body: str) -> str:
    return REGISTRATION_TEMPLATE.format(name=name, body=body)


class CollectLiteralsTest(unittest.TestCase):
    def test_plain_literal_is_reported(self) -> None:
        text = registration("browser_close_all", '"Close all browser tabs"')

        literals = collect_literals(text)

        self.assertEqual([(literal.tool, literal.text) for literal in literals], [
            ("browser_close_all", "Close all browser tabs"),
        ])

    def test_lambda_literal_is_reported(self) -> None:
        text = registration("browser_navigate", 'tool -> "Open the page"')

        literals = collect_literals(text)

        self.assertEqual([literal.tool for literal in literals], ["browser_navigate"])

    def test_string_resource_lookup_is_allowed(self) -> None:
        text = registration(
            "browser_close_all",
            "s(R.string.toolreg_browser_close_all_desc)",
        )

        self.assertEqual(collect_literals(text), [])

    def test_interpolated_template_is_still_a_literal(self) -> None:
        # Interpolation happens in Kotlin at runtime, not in the XML resource,
        # so this is caught: the value belongs in a %1$s placeholder instead.
        text = registration("browser_press_key", 'tool -> "Press key ${key}"')

        literals = collect_literals(text)

        self.assertEqual([literal.tool for literal in literals], ["browser_press_key"])

    def test_linear_registration_is_still_checked(self) -> None:
        text = """registerSoftwareSettingsTool(handler, context, name = "list_model_configs",
        descriptionGenerator = { "List all model configs" }
) { t, tool -> t.listModelConfigs(tool) }
"""
        literals = collect_literals(text)

        self.assertEqual([literal.tool for literal in literals], ["list_model_configs"])

    def test_multiline_literal_is_reported(self) -> None:
        text = registration(
            "browser_wait_for",
            '"Wait for a condition\\n"\n                + "then continue"',
        )

        literals = collect_literals(text)

        self.assertEqual([literal.tool for literal in literals], ["browser_wait_for"])

    def test_line_numbers_point_at_the_literal(self) -> None:
        # "// header" is line 1, so the description on the 4th line is line 4.
        text = "// header\n" + registration("browser_close", '"Close"')

        literals = collect_literals(text)

        self.assertEqual([literal.line for literal in literals], [4])


class EnclosingCallTest(unittest.TestCase):
    def test_call_bounds_cover_the_whole_registration(self) -> None:
        text = registration("browser_close", '"Close"')

        start, end = enclosing_call(text, text.index("descriptionGenerator"))

        self.assertTrue(text[start:end].startswith("registerTool("))
        self.assertTrue(text[start:end].rstrip().endswith(")"))

    def test_nested_parentheses_do_not_truncate_the_call(self) -> None:
        text = registration(
            "browser_click",
            'tool -> s(R.string.toolreg_browser_click_desc, ref)',
        )

        start, end = enclosing_call(text, text.index("descriptionGenerator"))

        self.assertIn("ToolGetter", text[start:end])


if __name__ == "__main__":
    unittest.main()
