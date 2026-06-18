import { FormEvent, useState } from "react";
import { useCreateProduct, useProducts } from "../api/products";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { PaginatedDataTable } from "../components/DataTable";
import { TextField } from "../components/Field";
import { PageHeader } from "../components/PageHeader";
import { Panel } from "../components/Panel";
import { ErrorBlock, LoadingBlock, StateBlock } from "../components/StateBlock";
import { formatDateTime } from "../lib/format";

export function ProductsPage() {
  const [name, setName] = useState("");
  const productsQuery = useProducts();
  const createProduct = useCreateProduct();

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    createProduct.mutate(
      { name },
      {
        onSuccess: () => setName("")
      }
    );
  }

  return (
    <>
      <PageHeader title="Products" description="Manage product tenants that own templates and notification traffic." />

      <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
        <Panel title="Create product">
          <form className="grid gap-4" onSubmit={handleSubmit}>
            <TextField
              label="Product name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Billing"
              required
            />
            {createProduct.isError ? (
              <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-ruby">
                {createProduct.error.message}
              </p>
            ) : null}
            <Button type="submit" disabled={createProduct.isPending || !name.trim()}>
              {createProduct.isPending ? "Creating" : "Create product"}
            </Button>
          </form>
        </Panel>

        <section>
          {productsQuery.isLoading ? <LoadingBlock /> : null}
          {productsQuery.isError ? <ErrorBlock message={productsQuery.error.message} /> : null}
          {productsQuery.data?.length === 0 ? (
            <StateBlock title="No products found" message="Create a product to start managing templates." />
          ) : null}
          {productsQuery.data && productsQuery.data.length > 0 ? (
            <PaginatedDataTable
              headers={["Name", "Product ID", "Status", "Created", "Updated"]}
              rows={productsQuery.data}
              renderRow={(product) => (
                <tr key={product.id}>
                  <td className="px-4 py-3 font-medium">{product.name}</td>
                  <td className="px-4 py-3 text-slate-600">{product.id}</td>
                  <td className="px-4 py-3">
                    <Badge value={product.status} tone={product.status === "ACTIVE" ? "success" : "neutral"} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">{formatDateTime(product.createdAt)}</td>
                  <td className="px-4 py-3 text-slate-600">{formatDateTime(product.updatedAt)}</td>
                </tr>
              )}
            />
          ) : null}
        </section>
      </div>
    </>
  );
}
