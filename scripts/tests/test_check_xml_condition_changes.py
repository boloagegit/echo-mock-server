from pathlib import Path
import runpy
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class CheckXmlConditionChangesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = runpy.run_path(
            str(PROJECT_ROOT / "scripts" / "check-xml-condition-changes.py"),
            run_name="echo_check_xml_condition_changes",
        )

    def findings(self, rule):
        return [(f["type"], f["condition"]) for f in self.script["rule_findings"](rule)]

    def test_relative_paths_are_reported(self):
        rule = {"bodyCondition": "Order/Status=OPEN; Item/@sku=S1;//Order/Status=OPEN;/Root/Status=A;Status=B"}
        self.assertEqual(
            [("relativePath", "Order/Status=OPEN"), ("relativePath", "Item/@sku=S1")],
            self.findings(rule))

    def test_double_equals_is_reported_except_for_jsonpath(self):
        rule = {"bodyCondition": "Status==OPEN;$.status==OPEN;a!==b;Order/Status==X"}
        self.assertEqual(
            [("doubleEquals", "Status==OPEN"), ("relativePath", "Order/Status==X"),
             ("doubleEquals", "Order/Status==X")],
            self.findings(rule))

    def test_xpath_only_expressions_are_unchanged(self):
        # Order/*=A 會拆成欄位 Order/、運算子 *=（與伺服器相同），空的路徑段不是簡單路徑
        rule = {"bodyCondition": "Order/Item[1]=A;a/b/text()=c;ns:Order/Status=A;Order/*/Status=A;Order/*=A;a//b=c"}
        self.assertEqual([("relativePath", "a/b/text()=c")], self.findings(rule))

    def test_structured_conditions_are_walked(self):
        rule = {"matchSpec": {"all": [
            {"source": "body", "selector": {"xpath": "Order/Status"}, "op": "equals", "value": "A"},
            {"source": "body", "selector": {"xpath": "//Order/Status"}, "op": "equals", "value": "A"},
            {"not": {"legacy": {"source": "body", "condition": "Status==OPEN"}}},
            {"legacy": {"source": "query", "condition": "a==b"}},
            {"any": [{"source": "header", "name": "X", "op": "exists"}]},
        ]}}
        self.assertEqual(
            [("doubleEquals", "Status==OPEN"), ("relativePath", "xpath Order/Status")],
            sorted(self.findings(rule), key=lambda f: f[0]))

    def test_match_spec_as_text_and_rules_without_conditions(self):
        rule = {"matchSpec": '{"source":"body","selector":{"xpath":"A/B"},"op":"exists"}'}
        self.assertEqual([("relativePath", "xpath A/B")], self.findings(rule))
        self.assertEqual([], self.findings({}))
        self.assertEqual([], self.findings({"matchSpec": "not json"}))

    def test_report_text_explains_protocol_specific_impact(self):
        report = self.script["analyse"]([
            {"id": "h1", "protocol": "HTTP", "method": "POST", "matchKey": "/orders",
             "description": "訂單", "bodyCondition": "Order/Status=OPEN", "enabled": True},
            {"id": "j1", "protocol": "JMS", "queueName": "ORDER", "bodyCondition": "Status==OPEN", "enabled": False},
            {"id": "ok", "protocol": "HTTP", "bodyCondition": "//Order/Status=OPEN"},
        ])
        self.assertEqual(3, report["checked"])
        self.assertEqual(["h1", "j1"], [item["id"] for item in report["affected"]])
        text = self.script["render_text"](report)
        self.assertIn("POST /orders  「訂單」  (h1)", text)
        self.assertIn("以前一定比不到", text)
        self.assertIn("JMS ORDER", text)
        self.assertIn("[已停用]", text)

    def test_report_without_findings(self):
        text = self.script["render_text"](self.script["analyse"]([{"bodyCondition": "a=b"}]))
        self.assertIn("不會改變你的規則結果", text)
        self.assertNotIn("請確認", text)


if __name__ == "__main__":
    unittest.main()
