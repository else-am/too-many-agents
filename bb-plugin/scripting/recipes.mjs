// PC 1.21.1 adaptation of prismarine-recipe 1.5.0 and Mineflayer 4.39.0.
// Attribution and upstream license declarations: recipes.LICENSE.
//
// registry: { recipes: { [resultItemId]: rawRecipe[] }, items?: { [id]:
//   { variations?: { metadata: number }[] } } }. Use minecraft-data's tables.
// No runtime imports, native actions, inventory mutation or recipe-book lookup.
//
// Explicit corrections to upstream source defects:
// - Factories retain their own registry instead of overwriting module globals.
// - find checks result.metadata rather than the misspelled `meta` presence test;
//   numeric result enums remain metadata wildcards instead of throwing.
// - Deltas use the supplied outShape, not a second copy of inShape.
//
// Other upstream quirks remain: arrays passed to RecipeItem.fromEnum are treated
// as objects (the upstream `typeof === 'array'` branch is unreachable); falsy
// enum counts become 1; shape/ingredient consumption is one item per cell;
// zero deltas remain. Queries check net deltas, not ordered ingredient use, so
// synthetic recipes with returned ingredients may still need an initial seed.
// PC 1.21.1 minecraft-data has no outShape entries, even for cake: this module
// does not infer missing remainders or server/datapack recipes from observations.
export function createRecipeFactory(registry) {
  const recipes = registry.recipes;
  const items = registry.items || {};

  function RecipeItem(id, metadata, count) {
    this.id = id;
    this.metadata = metadata;
    this.count = count;
  }

  RecipeItem.fromEnum = function (value) {
    if (value === null) return new RecipeItem(-1, null, 1);
    switch (typeof value) {
      case 'number': return new RecipeItem(value, null, 1);
      case 'object': return new RecipeItem(value.id, value.metadata == null ? null : value.metadata, value.count || 1);
    }
  };

  RecipeItem.clone = function (recipeItem) {
    return new RecipeItem(recipeItem.id, recipeItem.metadata, recipeItem.count);
  };

  function Recipe(recipeEnumItem) {
    this.result = RecipeItem.fromEnum(recipeEnumItem.result);
    this.inShape = recipeEnumItem.inShape ? reformatShape(recipeEnumItem.inShape) : null;
    this.outShape = recipeEnumItem.outShape ? reformatShape(recipeEnumItem.outShape) : null;
    this.ingredients = recipeEnumItem.ingredients ? reformatIngredients(recipeEnumItem.ingredients) : null;
    this.delta = computeDelta(this);
    this.requiresTable = computeRequiresTable(this);
  }

  Recipe.find = function (itemType, metadata) {
    const results = [];
    (recipes[itemType] || []).forEach(function (recipeEnumItem) {
      const result = recipeEnumItem.result;
      if (metadata == null || result.metadata == null || result.metadata === metadata) {
        results.push(new Recipe(recipeEnumItem));
      }
    });
    return results;
  };

  function computeRequiresTable(recipe) {
    let spaceLeft = 4;
    if (recipe.inShape) {
      if (recipe.inShape.length > 2) return true;
      for (const row of recipe.inShape) {
        if (row.length > 2) return true;
        for (const item of row) if (item) spaceLeft -= 1;
      }
    }
    if (recipe.ingredients) spaceLeft -= recipe.ingredients.length;
    return spaceLeft < 0;
  }

  function computeDelta(recipe) {
    const delta = [];
    if (recipe.inShape) applyShape(recipe.inShape, -1);
    if (recipe.outShape) applyShape(recipe.outShape, 1);
    if (recipe.ingredients) {
      for (const item of recipe.ingredients) add(RecipeItem.clone(item));
    }
    add(RecipeItem.clone(recipe.result));
    return delta;

    function add(item) {
      for (const d of delta) {
        if (d.id === item.id && d.metadata === item.metadata) {
          d.count += item.count;
          return;
        }
      }
      delta.push(item);
    }

    function applyShape(shape, direction) {
      for (const row of shape) {
        for (const source of row) {
          if (source.id !== -1) {
            const item = RecipeItem.clone(source);
            item.count = direction;
            add(item);
          }
        }
      }
    }
  }

  function normalizeMetadata(item) {
    if (item.metadata == null || item.id === -1) return item;
    if (item.metadata >= 32767) {
      item.metadata = null;
      return item;
    }
    const itemData = items[item.id];
    if (itemData && itemData.variations) {
      const validMetadata = itemData.variations.map(v => v.metadata);
      if (validMetadata.indexOf(item.metadata) === -1) item.metadata = null;
    }
    return item;
  }

  function reformatShape(shape) {
    const out = new Array(shape.length);
    for (let y = 0; y < shape.length; ++y) {
      const row = shape[y];
      const outRow = out[y] = new Array(row.length);
      for (let x = 0; x < outRow.length; ++x) outRow[x] = normalizeMetadata(RecipeItem.fromEnum(row[x]));
    }
    return out;
  }

  function reformatIngredients(ingredients) {
    const out = new Array(ingredients.length);
    for (let i = 0; i < out.length; ++i) {
      const item = normalizeMetadata(RecipeItem.fromEnum(ingredients[i]));
      item.count = -1;
      out[i] = item;
    }
    return out;
  }

  return { Recipe, RecipeItem };
}

// Install once with createRecipeFactory(registry). Each call reads the current
// bot.inventory.count(id, metadata); hydrated Window supplies slot/metadata rules.
// craftingTable retains upstream truthiness semantics, without a native lookup.
export function installRecipeQueries(bot, { Recipe }) {
  function recipesFor(itemType, metadata, minResultCount, craftingTable) {
    minResultCount = minResultCount ?? 1;
    const results = [];
    Recipe.find(itemType, metadata).forEach(recipe => {
      if (requirementsMetForRecipe(recipe, minResultCount, craftingTable)) results.push(recipe);
    });
    return results;
  }

  function recipesAll(itemType, metadata, craftingTable) {
    const results = [];
    Recipe.find(itemType, metadata).forEach(recipe => {
      if (!recipe.requiresTable || craftingTable) results.push(recipe);
    });
    return results;
  }

  function requirementsMetForRecipe(recipe, minResultCount, craftingTable) {
    if (recipe.requiresTable && !craftingTable) return false;
    const craftCount = Math.ceil(minResultCount / recipe.result.count);
    for (const d of recipe.delta) {
      if (bot.inventory.count(d.id, d.metadata) + d.count * craftCount < 0) return false;
    }
    return true;
  }

  bot.recipesFor = recipesFor;
  bot.recipesAll = recipesAll;
}
