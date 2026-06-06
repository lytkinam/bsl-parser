package com.github._1c_syntax.bsl.parser.sdql.api;

import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsModel;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsNode;
import com.github._1c_syntax.bsl.parser.sdql.full_pars.FullParsSelectField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeResolverImplTest {

  @Test
  void testResolveById() {
    FullParsModel model = new FullParsModel();
    FullParsNode node = new FullParsNode();
    node.setId(42);
    node.setName("ВТ_Тест");
    model.getNodes().add(node);

    NodeResolver resolver = new NodeResolverImpl();
    FullParsNode result = resolver.resolve(model, 42, null);

    assertThat(result.getName()).isEqualTo("ВТ_Тест");
  }

  @Test
  void testResolveByName() {
    FullParsModel model = new FullParsModel();
    FullParsNode node = new FullParsNode();
    node.setId(42);
    node.setName("ВТ_Тест");
    model.getNodes().add(node);

    NodeResolver resolver = new NodeResolverImpl();
    FullParsNode result = resolver.resolve(model, null, "ВТ_Тест");

    assertThat(result.getId()).isEqualTo(42);
  }

  @Test
  void testResolveNotFound() {
    FullParsModel model = new FullParsModel();

    NodeResolver resolver = new NodeResolverImpl();
    assertThatThrownBy(() -> resolver.resolve(model, null, "ВТ_Несуществующая"))
      .isInstanceOf(NodeNotFoundException.class)
      .hasMessageContaining("Node not found");
  }

  @Test
  void testResolveField() {
    FullParsNode node = new FullParsNode();
    FullParsSelectField field = new FullParsSelectField();
    field.setAlias("Поле1");
    node.setSelect(new ArrayList<>());
    node.getSelect().add(field);

    NodeResolver resolver = new NodeResolverImpl();
    FullParsSelectField result = resolver.resolveField(node, "Поле1");

    assertThat(result.getAlias()).isEqualTo("Поле1");
  }

  @Test
  void testResolveFieldNotFound() {
    FullParsNode node = new FullParsNode();
    node.setSelect(new ArrayList<>());

    NodeResolver resolver = new NodeResolverImpl();
    assertThatThrownBy(() -> resolver.resolveField(node, "Поле1"))
      .isInstanceOf(AliasNotFoundException.class)
      .hasMessageContaining("Alias 'Поле1' not found");
  }
}
